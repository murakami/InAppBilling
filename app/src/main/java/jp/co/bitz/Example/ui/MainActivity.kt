package jp.co.bitz.example.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import jp.co.bitz.example.BillingViewModel
import jp.co.bitz.example.R
import jp.co.bitz.example.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var billingViewModel: BillingViewModel
    private var activityMainBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        billingViewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(getApplication()))
            .get(BillingViewModel::class.java)

        billingViewModel.messages.observe(this) { message ->
            val snackbar = Snackbar.make(activityMainBinding.mainLayout, message, Snackbar.LENGTH_SHORT)
            snackbar.show()
        }

        lifecycle.addObserver(billingViewModel.billingLifecycleObserver)
    }
}