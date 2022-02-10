package jp.co.bitz.example

import android.app.Activity
import android.util.Log
import androidx.lifecycle.LifecycleObserver
import jp.co.bitz.example.billing.BillingDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class BillingRepository(
    private val billingDataSource: BillingDataSource,
    private val defaultScope: CoroutineScope
) {
    private val exampleMessages: MutableSharedFlow<String> = MutableSharedFlow()

    /**
     * 購入フローからのメッセージを送信する.
     */
    private fun postMessagesFromBillingFlow() {
        defaultScope.launch {
            try {
                billingDataSource.getNewPurchases().collect { skuList ->
                    for ( sku in skuList ) {
                        when (sku) {
                            SKU_CONSUMABLE -> exampleMessages.emit("consumable")
                            SKU_NON_CONSUMABLE -> exampleMessages.emit("non-consumable")
                            SKU_SUBSCRIPTION_01,
                            SKU_SUBSCRIPTION_02 -> {
                                billingDataSource.refreshPurchases()
                                exampleMessages.emit("subscriptions")
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Collection complete")
            }
            Log.d(TAG, "Collection Coroutine Scope Exited")
        }
    }

    /**
     * 自動購読のアップグレード/ダウングレードも対応.
     * @param activity
     * @param sku
     */
    fun buySku(activity: Activity, sku: String) {
        var oldSku: String? = null
        when (sku) {
            SKU_SUBSCRIPTION_01 -> oldSku = SKU_SUBSCRIPTION_02
            SKU_SUBSCRIPTION_02 -> oldSku = SKU_SUBSCRIPTION_01
        }
        if (oldSku == null) {
            billingDataSource.launchBillingFlow(activity, sku)
        } else {
            billingDataSource.launchBillingFlow(activity, sku, oldSku)
        }
    }

    /**
     * 購入確認.
     * @param sku 商品識別子
     * @return 購入済みで真を返す
     */
    fun isPurchased(sku: String): Flow<Boolean> {
        return billingDataSource.isPurchased(sku)
    }

    /**
     * 購入可能か？
     * @param sku 商品識別子
     * @return Flow<Boolean> 購入可能で真を返す
     */
    fun canPurchase(sku: String): Flow<Boolean> {
        return billingDataSource.canPurchase(sku)
    }

    suspend fun refreshPurchases() {
        billingDataSource.refreshPurchases()
    }

    val billingLifecycleObserver: LifecycleObserver
        get() = billingDataSource

    // 商品情報から表題を取り出して返す.
    fun getSkuTitle(sku: String): Flow<String> {
        return billingDataSource.getSkuTitle(sku)
    }

    fun getSkuPrice(sku: String): Flow<String> {
        return billingDataSource.getSkuPrice(sku)
    }

    fun getSkuDescription(sku: String): Flow<String> {
        return billingDataSource.getSkuDescription(sku)
    }

    val messages: Flow<String>
        get() = exampleMessages

    suspend fun sendMessage(msg: String) {
        exampleMessages.emit(msg)
    }

    val billingFlowInProcess: Flow<Boolean>
        get() = billingDataSource.getBillingFlowInProcess()

    fun debugConsumeNonConsumable() {
        CoroutineScope(Dispatchers.Main).launch {
            billingDataSource.consumeInappPurchase(SKU_NON_CONSUMABLE)
        }
    }

    companion object {
        const val SKU_CONSUMABLE = "jp.co.bitz.Example.consumable_01"
        const val SKU_NON_CONSUMABLE = "jp.co.bitz.Example.non_consumable_01"
        const val SKU_SUBSCRIPTION_01 = "jp.co.bitz.Example.renewable_subscription_01"
        const val SKU_SUBSCRIPTION_02 = "jp.co.bitz.Example.renewable_subscription_02"
        val TAG = BillingRepository::class.simpleName
        val INAPP_SKUS = arrayOf(SKU_CONSUMABLE, SKU_NON_CONSUMABLE)
        val SUBSCRIPTION_SKUS = arrayOf(
            SKU_SUBSCRIPTION_01,
            SKU_SUBSCRIPTION_02
        )
        val AUTO_CONSUME_SKUS = arrayOf(SKU_CONSUMABLE)
    }

    init {
        postMessagesFromBillingFlow()
    }
}
