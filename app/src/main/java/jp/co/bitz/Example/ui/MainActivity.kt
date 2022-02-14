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

class MainActivity : AppCompatActivity() {
    private lateinit var billingViewModel: BillingViewModel
    private lateinit var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        val view = activityMainBinding.root
        //setContentView(R.layout.activity_main)
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

        lifecycle.addObserver(billingViewModel.billingLifecycleObserver)
    }
}