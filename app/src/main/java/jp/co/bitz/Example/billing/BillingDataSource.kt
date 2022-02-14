package jp.co.bitz.example.billing

import android.app.Activity
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet
import kotlin.math.min

private const val RECONNECT_TIMER_START_MILLISECONDS = 1L * 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L // 15 minutes
private const val SKU_DETAILS_REQUERY_TIME = 1000L * 60L * 60L * 4L // 4 hours

class BillingDataSource private constructor(
    application: Application,
    private val defaultScope: CoroutineScope,
    knownInappSKUs: Array<String>?,
    knownSubscriptionSKUs: Array<String>?,
    autoConsumeSKUs: Array<String>?
) : DefaultLifecycleObserver, PurchasesUpdatedListener, BillingClientStateListener {
    // Google Play 請求サービス・ライブラリ
    private val billingClient: BillingClient

    // 購入可能商品リスト
    private val knownInappSKUs: List<String>?
    private val knownSubscriptionSKUs: List<String>?

    // 自動消費する商品リスト
    private val knownAutoConsumeSKUs: MutableSet<String>

    // 再接続までの時間
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS

    // 最後に接続した購入可能商品リストの取得時間
    private var skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME

    private enum class SkuState {
        SKU_STATE_UNPURCHASED,               // 未購入
        SKU_STATE_PENDING,                   // 保留
        SKU_STATE_PURCHASED,                 // 購入
        SKU_STATE_PURCHASED_AND_ACKNOWLEDGED // 購入および承認
    }

    private val skuStateMap: MutableMap<String, MutableStateFlow<SkuState>> = HashMap()
    private val skuDetailsMap: MutableMap<String, MutableStateFlow<SkuDetails?>> = HashMap()

    private val purchaseConsumptionInProcess: MutableSet<Purchase> = HashSet()
    private val newPurchaseFlow = MutableSharedFlow<List<String>>(extraBufferCapacity = 1)
    private val purchaseConsumedFlow = MutableSharedFlow<List<String>>()
    private val billingFlowInProcess = MutableStateFlow(false)

    /**
     * Google Play 接続終了.
     */
    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onBillingSetupFinished: $responseCode $debugMessage")
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                // 接続成功.
                reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
                defaultScope.launch {
                    querySkuDetailsAsync() // 購入可能商品リストを取得する.
                    refreshPurchases() // 購入状況を照会する.
                }
            }
            else -> retryBillingServiceConnectionWithExponentialBackoff() // 再接続
        }
    }

    /**
     * Google Play 切断.
     */
    override fun onBillingServiceDisconnected() {
        // 再接続
        retryBillingServiceConnectionWithExponentialBackoff()
    }

    /**
     * 再接続までの時間を計算して、接続を試みます.
     */
    private fun retryBillingServiceConnectionWithExponentialBackoff() {
        // 指定した時間が経過したのちに実行する
        handler.postDelayed(
            { billingClient.startConnection(this@BillingDataSource) },
            reconnectMilliseconds
        )
        reconnectMilliseconds = min(
            reconnectMilliseconds * 2,
            RECONNECT_TIMER_MAX_TIME_MILLISECONDS
        )
    }

    /**
     * initializeFlowsから呼び出される. Flowオブジェクトを生成する.
     * @param skuList 商品リスト.
     */
    private fun addSkuFlows(skuList: List<String>?) {
        for (sku in skuList!!) {
            val skuState = MutableStateFlow(SkuState.SKU_STATE_UNPURCHASED)
            val details = MutableStateFlow<SkuDetails?>(null)
            details.subscriptionCount.map { count -> count > 0 } // 活性/非活性フラグに割り当てる.
                .distinctUntilChanged() // 真偽の変更時に反応する
                .onEach { isActive -> // アクションを設定する
                    if (isActive && (SystemClock.elapsedRealtime() - skuDetailsResponseTime > SKU_DETAILS_REQUERY_TIME)) {
                        skuDetailsResponseTime = SystemClock.elapsedRealtime()
                        Log.v(TAG, "Skus not fresh, requerying")
                        querySkuDetailsAsync() // 購入可能商品リストを取得する.
                    }
                }
                .launchIn(defaultScope) // 発火する
            skuStateMap[sku] = skuState
            skuDetailsMap[sku] = details
        }
    }

    /**
     * 購入可能商品リストに対してFlowオブジェクトを生成して、商品情報と決済状況を確認できるようにする.
     */
    private fun initializeFlows() {
        addSkuFlows(knownInappSKUs)
        addSkuFlows(knownSubscriptionSKUs)
    }

    fun getNewPurchases() = newPurchaseFlow.asSharedFlow()

    /**
     * 消費済み購入を監視するFlow.
     * @return 消費済み購入の商品.
     */
    fun getConsumedPurchases() = purchaseConsumedFlow.asSharedFlow()

    /**
     * 商品が購入と承認されているかを確認する.
     * @return 商品の購入状態のFlow.
     */
    fun isPurchased(sku: String): Flow<Boolean> {
        val skuStateFLow = skuStateMap[sku]!!
        return skuStateFLow.map { skuState -> skuState == SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED }
    }

    /**
     * 商品が、購入可能であり、かつ、未購入かどうかを確認する.
     * @return 商品の購入状態のFlow.
     */
    fun canPurchase(sku: String): Flow<Boolean> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        val skuStateFlow = skuStateMap[sku]!!

        // 二つのflowをまとめる
        return skuStateFlow.combine(skuDetailsFlow) { skuState, skuDetails ->
            skuState == SkuState.SKU_STATE_UNPURCHASED && skuDetails != null
        }
    }

    /**
     * 商品名を返す.
     * @param sku 商品識別子.
     * @return 商品名.
     */
    fun getSkuTitle(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.title
        }
    }

    /**
     * 商品の金額を返す.
     * @param sku 商品のsku.
     * @return 商品の金額.
     */
    fun getSkuPrice(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.price
        }
    }

    /**
     * 商品の説明を返す.
     * @param sku 商品のsku.
     * @return 商品の説明.
     */
    fun getSkuDescription(sku: String): Flow<String> {
        val skuDetailsFlow = skuDetailsMap[sku]!!
        return skuDetailsFlow.mapNotNull { skuDetails ->
            skuDetails?.description
        }
    }

    /**
     * 購入可能商品リスト取得の結果を受け取る.
     */
    private fun onSkuDetailsResponse(billingResult: BillingResult, skuDetailsList: List<SkuDetails>?) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.i(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
                if (skuDetailsList == null || skuDetailsList.isEmpty()) {
                    Log.e(
                        TAG,
                        "onSkuDetailsResponse: " +
                                "Found null or empty SkuDetails. " +
                                "Check to see if the SKUs you requested are correctly published " +
                                "in the Google Play Console."
                    )
                } else {
                    for (skuDetails in skuDetailsList) {
                        val sku = skuDetails.sku
                        val detailsMutableFlow = skuDetailsMap[sku]
                        // 値を送信する
                        detailsMutableFlow?.tryEmit(skuDetails)
                            ?: Log.e(TAG, "Unknown sku: $sku")
                    }
                }
            }
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
            BillingClient.BillingResponseCode.ERROR ->
                Log.e(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.i(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED ->
                Log.wtf(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
            else -> Log.wtf(TAG, "onSkuDetailsResponse: $responseCode $debugMessage")
        }
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            skuDetailsResponseTime = SystemClock.elapsedRealtime()
        } else {
            skuDetailsResponseTime = -SKU_DETAILS_REQUERY_TIME
        }
    }

    /**
     * 購入可能商品リストを取得する.
     */
    private suspend fun querySkuDetailsAsync() {
        if (!knownInappSKUs.isNullOrEmpty()) {
            val skuDetailsResult = billingClient.querySkuDetails(
                SkuDetailsParams.newBuilder()
                    .setType(BillingClient.SkuType.INAPP)
                    .setSkusList(knownInappSKUs)
                    .build()
            )
            onSkuDetailsResponse(skuDetailsResult.billingResult, skuDetailsResult.skuDetailsList)
        }
        if (!knownSubscriptionSKUs.isNullOrEmpty()) {
            val skuDetailsResult = billingClient.querySkuDetails(
                SkuDetailsParams.newBuilder()
                    .setType(BillingClient.SkuType.SUBS)
                    .setSkusList(knownSubscriptionSKUs)
                    .build()
            )
            onSkuDetailsResponse(skuDetailsResult.billingResult, skuDetailsResult.skuDetailsList)
        }
    }

    /**
     * 購入済みの商品を取得する.
     */
    suspend fun refreshPurchases() {
        Log.d(TAG, "Refreshing purchases.")
        var purchasesResult = billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP)
        var billingResult = purchasesResult.billingResult
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
        } else {
            processPurchaseList(purchasesResult.purchasesList, knownInappSKUs)
        }
        purchasesResult = billingClient.queryPurchasesAsync(BillingClient.SkuType.SUBS)
        billingResult = purchasesResult.billingResult
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting subscriptions: " + billingResult.debugMessage)
        } else {
            processPurchaseList(purchasesResult.purchasesList, knownSubscriptionSKUs)
        }
        Log.d(TAG, "Refreshing purchases finished.")
    }

    /**
     * 購入情報を取得する.
     * @param skus 商品識別子の配列.
     * @param skuType 商品種別(一回限りか？定期購入か？).
     * @return 商品情報リスト.
     */
    private suspend fun getPurchases(skus: Array<String>, skuType: String): List<Purchase> {
        val purchasesResult = billingClient.queryPurchasesAsync(skuType)
        val billingResult = purchasesResult.billingResult
        val returnPurchasesList: MutableList<Purchase> = LinkedList()
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
        } else {
            val purchasesList = purchasesResult.purchasesList
            for (purchase in purchasesList) {
                for (sku in skus) {
                    for (purchaseSku in purchase.skus) {
                        if (purchaseSku == sku) {
                            returnPurchasesList.add(purchase)
                        }
                    }
                }
            }
        }
        return returnPurchasesList
    }

    /**
     * 一回限りの商品を消費する.
     */
    suspend fun consumeInappPurchase(sku: String) {
        val purchasesResult = billingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP)
        val billingResult = purchasesResult.billingResult
        val purchasesList = purchasesResult.purchasesList
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.e(TAG, "Problem getting purchases: " + billingResult.debugMessage)
        } else {
            for (purchase in purchasesList) {
                // for right now any bundle of SKUs must all be consumable
                for (purchaseSku in purchase.skus) {
                    if (purchaseSku == sku) {
                        consumePurchase(purchase)
                        return
                    }
                }
            }
        }
        Log.e(TAG, "Unable to consume SKU: $sku Sku not found.")
    }

    /**
     * 購入情報から状態Flowを更新する.
     */
    private fun setSkuStateFromPurchase(purchase: Purchase) {
        for (purchaseSku in purchase.skus) {
            val skuStateFlow = skuStateMap[purchaseSku]
            if (null == skuStateFlow) {
                Log.e(
                    TAG,
                    "Unknown SKU " + purchaseSku + ". Check to make " +
                            "sure SKU matches SKUS in the Play developer console."
                )
            } else {
                when (purchase.purchaseState) {
                    Purchase.PurchaseState.PENDING -> skuStateFlow.tryEmit(SkuState.SKU_STATE_PENDING)
                    Purchase.PurchaseState.UNSPECIFIED_STATE -> skuStateFlow.tryEmit(SkuState.SKU_STATE_UNPURCHASED)
                    Purchase.PurchaseState.PURCHASED -> if (purchase.isAcknowledged) {
                        skuStateFlow.tryEmit(SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED)
                    } else {
                        skuStateFlow.tryEmit(SkuState.SKU_STATE_PURCHASED)
                    }
                    else -> Log.e(TAG, "Purchase in unknown state: " + purchase.purchaseState)
                }
            }
        }
    }

    /**
     * 商品識別子の状態を更新する.
     */
    private fun setSkuState(sku: String, newSkuState: SkuState) {
        val skuStateFlow = skuStateMap[sku]
        skuStateFlow?.tryEmit(newSkuState)
            ?: Log.e(
                TAG,
                "Unknown SKU " + sku + ". Check to make " +
                        "sure SKU matches SKUS in the Play developer console."
            )
    }

    /**
     * 購入情報のリストを受け取る.
     */
    private fun processPurchaseList(purchases: List<Purchase>?, skusToUpdate: List<String>?) {
        val updatedSkus = HashSet<String>()
        if (null != purchases) {
            for (purchase in purchases) {
                // 状態Flowが存在しない商品識別子を無視する.
                for (sku in purchase.skus) {
                    val skuStateFlow = skuStateMap[sku]
                    if (null == skuStateFlow) {
                        Log.e(
                            TAG,
                            "Unknown SKU " + sku + ". Check to make " +
                                    "sure SKU matches SKUS in the Play developer console."
                        )
                        continue
                    }
                    updatedSkus.add(sku)
                }
                // 署名を確認する. iOSでいうところのレシート検証.
                val purchaseState = purchase.purchaseState
                if (purchaseState == Purchase.PurchaseState.PURCHASED) {
                    // 不正な署名の場合は無視する.
                    if (!isSignatureValid(purchase)) {
                        Log.e(
                            TAG,
                            "Invalid signature. Check to make sure your " +
                                    "public key is correct."
                        )
                        continue
                    }
                    // 購入状態を設定する.
                    setSkuStateFromPurchase(purchase)
                    var isConsumable = false // 消耗型(Non-consumable)プロダクト
                    defaultScope.launch {
                        for (sku in purchase.skus) {
                            if (knownAutoConsumeSKUs.contains(sku)) {
                                isConsumable = true // 消耗型(Consumable)プロダクト
                            } else {
                                if (isConsumable) {
                                    Log.e(TAG, "Purchase cannot contain a mixture of consumable" +
                                            "and non-consumable items: " + purchase.skus.toString())
                                    isConsumable = false
                                    break
                                }
                            }
                        }
                        if (isConsumable) { // 消耗型(Consumable)プロダクト
                            consumePurchase(purchase) // 消費する
                            newPurchaseFlow.tryEmit(purchase.skus)
                        } else if (!purchase.isAcknowledged) { // 非消耗型(Non-consumable)プロダクト
                            // 承認する
                            val billingResult = billingClient.acknowledgePurchase(
                                AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.purchaseToken)
                                    .build()
                            )
                            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                                Log.e(TAG, "Error acknowledging purchase: ${purchase.skus.toString()}")
                            } else {
                                // 承認済み
                                for (sku in purchase.skus) {
                                    setSkuState(sku, SkuState.SKU_STATE_PURCHASED_AND_ACKNOWLEDGED)
                                }
                            }
                            newPurchaseFlow.tryEmit(purchase.skus)
                        }
                    }
                } else {
                    // 状態Flowを更新する
                    setSkuStateFromPurchase(purchase)
                }
            }
        } else {
            Log.d(TAG, "Empty purchase list.")
        }
        // 購入状態を初期化する.
        if (null != skusToUpdate) {
            for (sku in skusToUpdate) {
                if (!updatedSkus.contains(sku)) {
                    setSkuState(sku, SkuState.SKU_STATE_UNPURCHASED)
                }
            }
        }
    }

    /**
     * 消費する.
     * @param purchase 購入情報.
     */
    private suspend fun consumePurchase(purchase: Purchase) {
        // 未消費チェック
        if (purchaseConsumptionInProcess.contains(purchase)) {
            // 消費済み
            return
        }
        purchaseConsumptionInProcess.add(purchase)
        val consumePurchaseResult = billingClient.consumePurchase(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        )

        purchaseConsumptionInProcess.remove(purchase)
        if (consumePurchaseResult.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Consumption successful. Emitting sku.")
            defaultScope.launch {
                purchaseConsumedFlow.emit(purchase.skus)
            }
            // 消費した.
            for (sku in purchase.skus) {
                setSkuState(sku, SkuState.SKU_STATE_UNPURCHASED)
            }
        } else {
            Log.e(TAG, "Error while consuming: ${consumePurchaseResult.billingResult.debugMessage}")
        }
    }

    /**
     * 購入フローを起動する.
     *
     * @param activity 起動するアクティビティ
     * @param sku 購入するSKU (商品 ID)
     * @param upgradeSkusVarargs 定期購読をアップグレードするSKU
     * @return 成功したら真を返す
     */
    fun launchBillingFlow(activity: Activity?, sku: String, vararg upgradeSkusVarargs: String) {
        val skuDetails = skuDetailsMap[sku]?.value
        if (null != skuDetails) {
            val billingFlowParamsBuilder = BillingFlowParams.newBuilder()
            billingFlowParamsBuilder.setSkuDetails(skuDetails)
            val upgradeSkus = arrayOf(*upgradeSkusVarargs)
            defaultScope.launch {
                val heldSubscriptions = getPurchases(upgradeSkus, BillingClient.SkuType.SUBS)
                when (heldSubscriptions.size) {
                    1 -> {
                        val purchase = heldSubscriptions[0]
                        billingFlowParamsBuilder.setSubscriptionUpdateParams(
                            BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                                .setOldSkuPurchaseToken(purchase.purchaseToken)
                                .build()
                        )
                    }
                    0 -> {
                    }
                    else -> Log.e(
                        TAG,
                        heldSubscriptions.size.toString() +
                                " subscriptions subscribed to. Upgrade not possible."
                    )
                }
                val br = billingClient.launchBillingFlow(
                    activity!!,
                    billingFlowParamsBuilder.build()
                )
                if (br.responseCode == BillingClient.BillingResponseCode.OK) {
                    billingFlowInProcess.emit(true)
                } else {
                    Log.e(TAG, "Billing failed: + " + br.debugMessage)
                }
            }
        } else {
            Log.e(TAG, "SkuDetails not found for: $sku")
        }
    }

    /**
     * 購入フローの状態を返す.
     * @return 購入フローの状態.
     */
    fun getBillingFlowInProcess(): Flow<Boolean> {
        return billingFlowInProcess.asStateFlow()
    }

    /**
     * 購入フローから呼ばれる.
     * @param billingResult 購入フローの結果.
     * @param list 購入情報のリスト.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, list: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> if (null != list) {
                processPurchaseList(list, null)
                return
            } else Log.d(TAG, "Null Purchase List Returned from OK response!")
            BillingClient.BillingResponseCode.USER_CANCELED -> Log.i(TAG, "onPurchasesUpdated: User canceled the purchase")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> Log.i(TAG, "onPurchasesUpdated: The user already owns this item")
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> Log.e(
                TAG,
                "onPurchasesUpdated: Developer error means that Google Play " +
                        "does not recognize the configuration. If you are just getting started, " +
                        "make sure you have configured the application correctly in the " +
                        "Google Play Console. The SKU product ID must match and the APK you " +
                        "are using must be signed with release keys."
            )
            else -> Log.d(TAG, "BillingResult [" + billingResult.responseCode + "]: " + billingResult.debugMessage)
        }
        defaultScope.launch {
            billingFlowInProcess.emit(false)
        }
    }

    /**
     * レシート検証. @see [Security]
     */
    private fun isSignatureValid(purchase: Purchase): Boolean {
        return Security.verifyPurchase(purchase.originalJson, purchase.signature)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Log.d(TAG, "ON_RESUME")
        // 購入フロー完了後の購入への配慮.
        if (!billingFlowInProcess.value) {
            if (billingClient.isReady) {
                defaultScope.launch {
                    refreshPurchases()
                }
            }
        }
    }

    companion object {
        private val TAG = "InAppBilling:" + BillingDataSource::class.java.simpleName

        @Volatile
        private var sInstance: BillingDataSource? = null
        private val handler = Handler(Looper.getMainLooper())

        // シングルトン.
        @JvmStatic
        fun getInstance(
            application: Application,
            defaultScope: CoroutineScope,
            knownInappSKUs: Array<String>?,
            knownSubscriptionSKUs: Array<String>?,
            autoConsumeSKUs: Array<String>?
        ) = sInstance ?: synchronized(this) {
            sInstance ?: BillingDataSource(
                application,
                defaultScope,
                knownInappSKUs,
                knownSubscriptionSKUs,
                autoConsumeSKUs
            )
                .also { sInstance = it }
        }
    }

    /**
     * 構築子.
     * @param application アプリケーション・クラス.
     * @param knownInappSKUs 一回限りのアイテム
     * @param knownSubscriptionSKUs 定期購入
     */
    init {
        this.knownInappSKUs = if (knownInappSKUs == null) {
            ArrayList()
        } else {
            listOf(*knownInappSKUs)
        }
        this.knownSubscriptionSKUs = if (knownSubscriptionSKUs == null) {
            ArrayList()
        } else {
            listOf(*knownSubscriptionSKUs)
        }
        knownAutoConsumeSKUs = HashSet()
        if (autoConsumeSKUs != null) {
            knownAutoConsumeSKUs.addAll(listOf(*autoConsumeSKUs))
        }
        initializeFlows()
        billingClient = BillingClient.newBuilder(application)
            .setListener(this)
            .enablePendingPurchases()
            .build()
        billingClient.startConnection(this)
    }
}