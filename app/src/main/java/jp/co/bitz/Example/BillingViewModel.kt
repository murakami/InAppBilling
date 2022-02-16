package jp.co.bitz.example

import android.app.Activity
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.util.HashMap

/**
 * BillingRepositoryからLiveDataで受け取る.
 */
class BillingViewModel(private val billingRepository: BillingRepository) : ViewModel() {
    val messages: LiveData<String>
        get() = billingRepository.messages.asLiveData()

    val debugWrite: LiveData<String>
        get() = billingRepository.debugWrite.asLiveData()

    val isNonConsumable: LiveData<Boolean>
        get() = billingRepository.isPurchased(BillingRepository.SKU_NON_CONSUMABLE).asLiveData()

    fun debugConsumeNonConsumable() {
        billingRepository.debugConsumeNonConsumable()
    }

    val billingLifecycleObserver: LifecycleObserver
        get() = billingRepository.billingLifecycleObserver

    class SkuDetails internal constructor(val sku: String, tdr: BillingRepository) {
        val title = tdr.getSkuTitle(sku).asLiveData()
        val description = tdr.getSkuDescription(sku).asLiveData()
        val price = tdr.getSkuPrice(sku).asLiveData()
    }

    fun getSkuDetails(sku: String): SkuDetails {
        return SkuDetails(sku, billingRepository)
    }

    fun canBuySku(sku: String): LiveData<Boolean> {
        return billingRepository.canPurchase(sku).asLiveData()
    }

    fun isPurchased(sku: String): LiveData<Boolean> {
        return billingRepository.isPurchased(sku).asLiveData()
    }

    /**
     * 購入フローを開始する.
     * @param アクティビティ
     * @return 購入フローを開始できたか？
     */
    fun buySku(activity: Activity, sku: String) {
        billingRepository.buySku(activity, sku)
    }

    val billingFlowInProcess: LiveData<Boolean>
        get() = billingRepository.billingFlowInProcess.asLiveData()

    fun sendMessage(message: String) {
        viewModelScope.launch {
            billingRepository.sendMessage(message)
        }
    }

    fun sendDebugWrite(message: String) {
        viewModelScope.launch {
            billingRepository.sendDebugWrite(message)
        }
    }

    class BillingViewModelFactory(private val billingRepository: BillingRepository) :
        ViewModelProvider.Factory {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BillingViewModel::class.java)) {
                return BillingViewModel(billingRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        val TAG = BillingViewModel::class.simpleName
        private val skuToResourceIdMap: MutableMap<String, Int> = HashMap()

        init {
        }
    }
}