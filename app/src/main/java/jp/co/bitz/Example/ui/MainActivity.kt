package jp.co.bitz.example.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import jp.co.bitz.example.BillingRepository
import jp.co.bitz.example.BillingViewModel
import jp.co.bitz.example.ExampleApplication
import jp.co.bitz.example.R
import jp.co.bitz.example.databinding.ActivityMainBinding

public class MainActivity : AppCompatActivity() {
    private lateinit var billingViewModel: BillingViewModel
    private lateinit var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        val view = activityMainBinding.root
        setContentView(view)

        val billingRepository = (getApplication() as ExampleApplication).appContainer.billingRepository
        val billingViewModelFactory = BillingViewModel.BillingViewModelFactory(billingRepository)
        billingViewModel = ViewModelProvider(this, billingViewModelFactory)
            .get(BillingViewModel::class.java)

        billingViewModel.messages.observe(this) { message ->
            val snackbar = Snackbar.make(activityMainBinding.mainLayout, message, Snackbar.LENGTH_SHORT)
            snackbar.show()
            val dbgMsg = "\n" + message
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }

        billingViewModel.debugWrite.observe(this) { message ->
            val dbgMsg = "\n" + message
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }

        activityMainBinding.buttonGetBuyConsumable.setOnClickListener {
            billingViewModel.buySku(this, BillingRepository.SKU_CONSUMABLE)
        }

        activityMainBinding.buttonGetBuyNonConsumable.setOnClickListener {
            billingViewModel.buySku(this, BillingRepository.SKU_NON_CONSUMABLE)
        }

        activityMainBinding.buttonDebugConsumeNonConsumable.setOnClickListener {
            billingViewModel.debugConsumeNonConsumable()
        }

        activityMainBinding.buttonGetBuyRenewableSubscription01.setOnClickListener {
            billingViewModel.buySku(this, BillingRepository.SKU_SUBSCRIPTION_01)
        }

        activityMainBinding.buttonGetBuyRenewableSubscription02.setOnClickListener {
            billingViewModel.buySku(this, BillingRepository.SKU_SUBSCRIPTION_02)
        }

        dumpSkuDetails(BillingRepository.SKU_CONSUMABLE)
        dumpSkuDetails(BillingRepository.SKU_NON_CONSUMABLE)
        dumpSkuDetails(BillingRepository.SKU_SUBSCRIPTION_01)
        dumpSkuDetails(BillingRepository.SKU_SUBSCRIPTION_02)

        dumpCanBuy(BillingRepository.SKU_CONSUMABLE)
        dumpCanBuy(BillingRepository.SKU_NON_CONSUMABLE)
        dumpCanBuy(BillingRepository.SKU_SUBSCRIPTION_01)
        dumpCanBuy(BillingRepository.SKU_SUBSCRIPTION_02)

        dumpIsPurchased(BillingRepository.SKU_CONSUMABLE)
        dumpIsPurchased(BillingRepository.SKU_NON_CONSUMABLE)
        dumpIsPurchased(BillingRepository.SKU_SUBSCRIPTION_01)
        dumpIsPurchased(BillingRepository.SKU_SUBSCRIPTION_02)

        lifecycle.addObserver(billingViewModel.billingLifecycleObserver)
    }

    fun dumpSkuDetails(sku: String) {
        val skuDetails = billingViewModel.getSkuDetails(sku)
        skuDetails.title.observe(this) { title ->
            val dbgMsg = "\n" + title
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }
        skuDetails.description.observe(this) { description ->
            val dbgMsg = "\n" + description
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }
        skuDetails.price.observe(this) { price ->
            val dbgMsg = "\n" + price
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }
    }

    fun dumpCanBuy(sku: String) {
        billingViewModel.canBuySku(sku).observe(this) { canPurchase ->
            var dbgMsg = "\n" + sku
            if (canPurchase) dbgMsg += " can buy."
            else dbgMsg += " can not buy."
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }
    }

    fun dumpIsPurchased(sku: String) {
        billingViewModel.isPurchased(sku).observe(this) { isPurchased ->
            var dbgMsg = "\n" + sku
            if (isPurchased) dbgMsg += " has been purchased."
            else dbgMsg += " not purchased."
            activityMainBinding.textViewDebugMessage.append(dbgMsg)
        }
    }
}