package jp.co.bitz.example

import android.app.Application
import jp.co.bitz.example.billing.BillingDataSource
import kotlinx.coroutines.GlobalScope

class ExampleApplication : Application() {
    lateinit var appContainer: AppContainer
    // アプリ全体で共有されるコンテナ.
    inner class AppContainer {
        private val applicationScope = GlobalScope
        private val billingDataSource = BillingDataSource.getInstance(
            this@ExampleApplication,
            applicationScope,
            BillingRepository.INAPP_SKUS,
            BillingRepository.SUBSCRIPTION_SKUS,
            BillingRepository.AUTO_CONSUME_SKUS
        )
        val billingRepository = BillingRepository(
            billingDataSource,
            applicationScope
        )
    }

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer()
    }
}