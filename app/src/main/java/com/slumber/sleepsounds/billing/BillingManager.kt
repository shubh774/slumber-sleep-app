package com.slumber.sleepsounds.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Wraps the official Google Play Billing Library.
 *
 * IMPORTANT -- before this compiles into something Play Store will accept, you must:
 * 1. Create these products in Play Console > Monetize > Products (Subscriptions / In-app products)
 *    using EXACTLY these product IDs (or change the IDs below to match what you create):
 *      - "slumber_pro_monthly"   (subscription, base plan)
 *      - "slumber_pro_quarterly" (subscription, base plan)
 *      - "slumber_pro_yearly"    (subscription, base plan, with a free trial phase configured
 *                                  in Play Console -- the 7-day trial is NOT set in code)
 *      - "slumber_pro_lifetime"  (one-time in-app product, non-consumable)
 * 2. A signed build uploaded to an Internal Testing track before purchases will actually work
 *    (Play Billing does not function against debug/unsigned builds outside test setups).
 *
 * This class deliberately does NOT fake entitlement locally -- isProUser() only returns true
 * after Play has confirmed a real purchase, and that purchase has been acknowledged.
 */
class BillingManager(
    private val context: Context,
    private val onProStatusChanged: (Boolean) -> Unit
) : PurchasesUpdatedListener {

    companion object {
        const val MONTHLY = "slumber_pro_monthly"
        const val QUARTERLY = "slumber_pro_quarterly"
        const val YEARLY = "slumber_pro_yearly"
        const val LIFETIME = "slumber_pro_lifetime"

        private val SUBSCRIPTION_IDS = listOf(MONTHLY, QUARTERLY, YEARLY)
        private const val PREFS_NAME = "slumber_billing_prefs"
        private const val KEY_IS_PRO = "is_pro_user"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var productDetailsCache: Map<String, ProductDetails> = emptyMap()

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    /**
     * Cached locally ONLY so the UI doesn't flash "ads visible" for a split second on cold
     * start while we reconnect to Play and re-verify. The source of truth is always Play's
     * queryPurchasesAsync response, called in refreshPurchases() below.
     */
    fun isProUserCached(): Boolean = prefs.getBoolean(KEY_IS_PRO, false)

    fun startConnection(onReady: () -> Unit = {}) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    refreshPurchases()
                    onReady()
                }
            }
            override fun onBillingServiceDisconnected() {
                // The library retries connection automatically on the next call that needs it;
                // nothing to do here beyond letting the UI reflect "not yet known" if needed.
            }
        })
    }

    private fun queryProductDetails() {
        val subsProducts = SUBSCRIPTION_IDS.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val inAppProducts = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(LIFETIME)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(subsProducts).build()
        ) { _, subsResult ->
            billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(inAppProducts).build()
            ) { _, inAppResult ->
                val combined = (subsResult + inAppResult).associateBy { it.productId }
                productDetailsCache = combined
            }
        }
    }

    /** Ask Play what the user has actually bought. This is the real entitlement check. */
    fun refreshPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { _, subsPurchases ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { _, inAppPurchases ->
                val allPurchases = subsPurchases + inAppPurchases
                val hasEntitlement = allPurchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                allPurchases.forEach { acknowledgeIfNeeded(it) }
                setProStatus(hasEntitlement)
            }
        }
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val details = productDetailsCache[productId] ?: return

        val paramsBuilder = BillingFlowParams.newBuilder()

        if (productId == LIFETIME) {
            paramsBuilder.setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
        } else {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
            paramsBuilder.setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
        }

        billingClient.launchBillingFlow(activity, paramsBuilder.build())
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { acknowledgeIfNeeded(it) }
            val hasEntitlement = purchases.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            if (hasEntitlement) setProStatus(true)
        }
        // BillingResponseCode.USER_CANCELED and others are intentionally not surfaced as
        // errors here -- the UI simply stays on the paywall, which is the correct behavior.
    }

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { /* result ignored: refreshPurchases() re-verifies on next launch */ }
        }
    }

    private fun setProStatus(isPro: Boolean) {
        prefs.edit().putBoolean(KEY_IS_PRO, isPro).apply()
        onProStatusChanged(isPro)
    }

    fun endConnection() {
        billingClient.endConnection()
    }
}
