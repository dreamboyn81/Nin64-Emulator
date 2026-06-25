package com.izzy2lost.nin64

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.util.concurrent.CopyOnWriteArraySet

object NoAdsPurchaseManager {
    const val PRODUCT_ID = "remove_ads"

    private const val TAG = "Nin64Billing"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val lock = Any()
    private val pendingReadyCallbacks = mutableListOf<(BillingResult) -> Unit>()

    private var billingClient: BillingClient? = null
    private var connecting = false

    class RemoveAdsOffer internal constructor(
        val formattedPrice: String,
        internal val productDetails: ProductDetails,
        internal val offerToken: String,
    )

    interface Listener {
        fun onNoAdsEntitlementChanged(adsRemoved: Boolean) = Unit
        fun onNoAdsPurchasePending() = Unit
        fun onNoAdsPurchaseCancelled() = Unit
        fun onNoAdsPurchaseError(message: String) = Unit
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun refreshEntitlement(
        context: Context,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        withReady(appContext) { setupResult ->
            if (setupResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "Billing setup failed: ${setupResult.debugMessage}")
                dispatchComplete(onComplete, AdsController.areAdsRemoved(appContext))
                return@withReady
            }

            val purchasesParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            getClient(appContext).queryPurchasesAsync(purchasesParams) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Purchase query failed: ${billingResult.debugMessage}")
                    dispatchComplete(onComplete, AdsController.areAdsRemoved(appContext))
                    return@queryPurchasesAsync
                }

                val purchaseFound = processPurchases(
                    context = appContext,
                    purchases = purchases,
                    notifyUser = false,
                )
                if (!purchaseFound) {
                    clearEntitlementAfterConfirmedQuery(appContext)
                }
                dispatchComplete(onComplete, AdsController.areAdsRemoved(appContext))
            }
        }
    }

    fun queryRemoveAdsOffer(
        context: Context,
        onComplete: (RemoveAdsOffer?, BillingResult) -> Unit,
    ) {
        val appContext = context.applicationContext
        withReady(appContext) { setupResult ->
            if (setupResult.responseCode != BillingClient.BillingResponseCode.OK) {
                dispatchOffer(onComplete, null, setupResult)
                return@withReady
            }

            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val queryParams = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()

            getClient(appContext).queryProductDetailsAsync(queryParams) { billingResult, productDetailsResult ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    dispatchOffer(onComplete, null, billingResult)
                    return@queryProductDetailsAsync
                }

                val productDetails = productDetailsResult.productDetailsList
                    .firstOrNull { details -> details.productId == PRODUCT_ID }
                val offerDetails = productDetails
                    ?.oneTimePurchaseOfferDetailsList
                    ?.firstOrNull { offer -> offer.rentalDetails == null }
                val offerToken = offerDetails?.offerToken

                val offer = if (productDetails != null && offerDetails != null && !offerToken.isNullOrBlank()) {
                    RemoveAdsOffer(
                        formattedPrice = offerDetails.formattedPrice,
                        productDetails = productDetails,
                        offerToken = offerToken,
                    )
                } else {
                    null
                }
                dispatchOffer(onComplete, offer, billingResult)
            }
        }
    }

    fun launchRemoveAdsPurchase(activity: Activity, offer: RemoveAdsOffer) {
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.productDetails)
            .setOfferToken(offer.offerToken)
            .build()
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        val billingResult = getClient(activity.applicationContext)
            .launchBillingFlow(activity, billingFlowParams)

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refreshEntitlement(activity) { adsRemoved ->
                    if (adsRemoved) {
                        notifyEntitlementChanged(true)
                    } else {
                        notifyPurchaseError(responseMessage(billingResult))
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> notifyPurchaseCancelled()
            else -> notifyPurchaseError(responseMessage(billingResult))
        }
    }

    private fun getClient(context: Context): BillingClient {
        billingClient?.let { return it }

        val appContext = context.applicationContext
        return BillingClient.newBuilder(appContext)
            .setListener { billingResult, purchases ->
                handlePurchaseUpdate(appContext, billingResult, purchases)
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()
            .also { client -> billingClient = client }
    }

    private fun withReady(context: Context, callback: (BillingResult) -> Unit) {
        val client = getClient(context)
        if (client.isReady) {
            mainHandler.post { callback(okResult()) }
            return
        }

        val shouldStartConnection = synchronized(lock) {
            pendingReadyCallbacks += callback
            if (connecting) {
                false
            } else {
                connecting = true
                true
            }
        }
        if (!shouldStartConnection) return

        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                val callbacks = synchronized(lock) {
                    connecting = false
                    pendingReadyCallbacks.toList().also { pendingReadyCallbacks.clear() }
                }
                mainHandler.post {
                    callbacks.forEach { readyCallback -> readyCallback(billingResult) }
                }
            }

            override fun onBillingServiceDisconnected() {
                synchronized(lock) {
                    connecting = false
                }
                Log.d(TAG, "Billing service disconnected.")
            }
        })
    }

    private fun handlePurchaseUpdate(
        context: Context,
        billingResult: BillingResult,
        purchases: List<Purchase>?,
    ) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    refreshEntitlement(context)
                } else {
                    processPurchases(
                        context = context,
                        purchases = purchases,
                        notifyUser = true,
                    )
                }
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                refreshEntitlement(context) { adsRemoved ->
                    if (adsRemoved) {
                        notifyEntitlementChanged(true)
                    } else {
                        notifyPurchaseError(responseMessage(billingResult))
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> notifyPurchaseCancelled()
            else -> notifyPurchaseError(responseMessage(billingResult))
        }
    }

    private fun processPurchases(
        context: Context,
        purchases: List<Purchase>,
        notifyUser: Boolean,
    ): Boolean {
        var hasPurchasedNoAds = false
        purchases
            .filter { purchase -> purchase.products.contains(PRODUCT_ID) }
            .forEach { purchase ->
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PURCHASED -> {
                        hasPurchasedNoAds = true
                        val wasRemoved = AdsController.areAdsRemoved(context)
                        AdsController.setAdsRemoved(context, true)
                        if (notifyUser || !wasRemoved) {
                            notifyEntitlementChanged(true)
                        }
                        acknowledgeIfNeeded(context, purchase)
                    }
                    Purchase.PurchaseState.PENDING -> {
                        if (notifyUser) {
                            notifyPurchasePending()
                        }
                    }
                    else -> Unit
                }
            }
        return hasPurchasedNoAds
    }

    private fun clearEntitlementAfterConfirmedQuery(context: Context) {
        val wasRemoved = AdsController.areAdsRemoved(context)
        AdsController.setAdsRemoved(context, false)
        if (wasRemoved) {
            notifyEntitlementChanged(false)
        }
    }

    private fun acknowledgeIfNeeded(context: Context, purchase: Purchase) {
        if (purchase.isAcknowledged) return

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        getClient(context).acknowledgePurchase(params) { billingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "No-ads purchase acknowledged.")
            } else {
                Log.w(TAG, "No-ads acknowledge failed: ${billingResult.debugMessage}")
            }
        }
    }

    private fun dispatchOffer(
        onComplete: (RemoveAdsOffer?, BillingResult) -> Unit,
        offer: RemoveAdsOffer?,
        billingResult: BillingResult,
    ) {
        mainHandler.post { onComplete(offer, billingResult) }
    }

    private fun dispatchComplete(
        onComplete: (Boolean) -> Unit,
        adsRemoved: Boolean,
    ) {
        mainHandler.post { onComplete(adsRemoved) }
    }

    private fun notifyEntitlementChanged(adsRemoved: Boolean) {
        mainHandler.post {
            listeners.forEach { listener ->
                listener.onNoAdsEntitlementChanged(adsRemoved)
            }
        }
    }

    private fun notifyPurchasePending() {
        mainHandler.post {
            listeners.forEach { listener -> listener.onNoAdsPurchasePending() }
        }
    }

    private fun notifyPurchaseCancelled() {
        mainHandler.post {
            listeners.forEach { listener -> listener.onNoAdsPurchaseCancelled() }
        }
    }

    private fun notifyPurchaseError(message: String) {
        mainHandler.post {
            listeners.forEach { listener -> listener.onNoAdsPurchaseError(message) }
        }
    }

    private fun okResult(): BillingResult {
        return BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .setDebugMessage("Billing client ready.")
            .build()
    }

    private fun responseMessage(billingResult: BillingResult): String {
        return billingResult.debugMessage
            .takeIf { message -> message.isNotBlank() }
            ?: "Google Play Billing response ${billingResult.responseCode}"
    }
}
