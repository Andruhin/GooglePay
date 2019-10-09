package com.tacitinnovations.googlepay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wallet.AutoResolveHelper
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.WalletConstants
import com.tacitinnovations.googlepay.Either.Left
import com.tacitinnovations.googlepay.Either.Right
import com.tacitinnovations.googlepay.Failure.LoadPaymentDataFailed
import com.tacitinnovations.googlepay.Failure.NotAvailableOnThisDevice
import com.tacitinnovations.googlepay.GooglePayCheckOut.Environment.Prod
import com.tacitinnovations.googlepay.GooglePayCheckOut.Environment.Test
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

interface GooglePayCheckOut {
  enum class Environment {
    Test, Prod
  }
  fun setPriceSource(price: () -> Long)
  fun initGooglePay(activity: AppCompatActivity, listener: (Either<Failure, Boolean>) -> Unit)
  fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
  fun payButton(): View

  class Impl(
          context: Context,
          root: ViewGroup? = null,
          payButton: View? = null,
          val environment: Environment = Prod,
          val paymentGateWayTokenization: JSONObject = JSONObject(Constants.DEFAULT_PAYMENT_GATEWAY_TOKENIZATION_PARAMETERS),
          val handlePayButtonVisibility: Boolean = true,
          val allowedCardNetworks: JSONArray = JSONArray(Constants.DEFAULT_SUPPORTED_NETWORKS),
          val allowedAuthMethods: JSONArray = JSONArray(Constants.SUPPORTED_METHODS),
          val currencyCode: String = Constants.DEFAULT_CURRENCY_CODE,
          val merchantName: String,
          val listener: (Either<Failure, PayResult>) -> Unit
  ) : GooglePayCheckOut {

    /**
     * A client for interacting with the Google Pay API.
     *
     * @see [PaymentsClient](https://developers.google.com/android/reference/com/google/android/gms/wallet/PaymentsClient)
     */
    private lateinit var paymentsClient: PaymentsClient
    private var googlePayButton: View =
      payButton ?: LayoutInflater.from(context).inflate(R.layout.googlepay_button, root, false)
    private var price: () -> Long = { -1 }

    init {
      if (handlePayButtonVisibility) {
        googlePayButton.visibility = View.GONE
      }
    }

    override fun payButton(): View = googlePayButton

    override fun initGooglePay(
      activity: AppCompatActivity,
      listener: (Either<Failure, Boolean>) -> Unit
    ) {
      paymentsClient = PaymentsUtil.createPaymentsClient(activity,
          if (environment == Test) WalletConstants.ENVIRONMENT_TEST else WalletConstants.ENVIRONMENT_PRODUCTION
          )
      possiblyShowGooglePayButton(allowedCardNetworks, allowedAuthMethods, listener)
      googlePayButton.setOnClickListener { requestPayment(activity) }
    }

    /**
     * The price should be multiply
     * (price * 1000000).roundToLong()
     */
    override fun setPriceSource(price: () -> Long) {
      this.price = price
    }


    private fun requestPayment(activity: AppCompatActivity) {
      val amount: Long = price.invoke()

      if (amount == -1L) {
        throw RuntimeException("Amount not set")
      }

      // Disables the button to prevent multiple clicks.
      googlePayButton.isClickable = false

      val paymentDataRequestJson = PaymentsUtil.getPaymentDataRequest(
        amount.microsToString(),
        allowedCardNetworks,
        allowedAuthMethods,
        currencyCode,
              paymentGateWayTokenization,
              merchantName
      )
      //  Log.e("RequestPayment", "amount.microsToString()"+amount.microsToString())
      if (paymentDataRequestJson == null) {
        Log.e("RequestPayment", "Can't fetch payment data request")
        return
      }
      val request = PaymentDataRequest.fromJson(paymentDataRequestJson.toString())

      // Since loadPaymentData may show the UI asking the user to select a payment method, we use
      // AutoResolveHelper to wait for the user interacting with it. Once completed,
      // onActivityResult will be called with the result.
      if (request != null) {
        AutoResolveHelper.resolveTask(
          paymentsClient.loadPaymentData(request), activity, LOAD_PAYMENT_DATA_REQUEST_CODE
        )
      }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
      when (requestCode) {
        // value passed in AutoResolveHelper
        LOAD_PAYMENT_DATA_REQUEST_CODE -> {
          when (resultCode) {
            Activity.RESULT_OK ->
              data?.let { intent ->
                PaymentData.getFromIntent(intent)?.let(::handlePaymentSuccess)
              }
            Activity.RESULT_CANCELED -> {
              // Nothing to do here normally - the user simply cancelled without selecting a
              // payment method.
            }

            AutoResolveHelper.RESULT_ERROR -> {
              AutoResolveHelper.getStatusFromIntent(data)?.let {
                handleError(it.statusCode)
              }
            }
          }
          // Re-enables the Google Pay payment button.
          googlePayButton.isClickable = true
        }
      }

    }


    private fun handleError(statusCode: Int) {
      Log.w("loadPaymentData failed", String.format("Error code: %d", statusCode))
      listener.invoke(Left(LoadPaymentDataFailed))
    }


    /**
     * PaymentData response object contains the payment information, as well as any additional
     * requested information, such as billing and shipping address.
     *
     * @param paymentData A response object returned by Google after a payer approves payment.
     * @see [Payment
     * Data](https://developers.google.com/pay/api/android/reference/object.PaymentData)
     */
    private fun handlePaymentSuccess(paymentData: PaymentData) {
      val paymentInformation = paymentData.toJson() ?: return

      try {
        // Token will be null if PaymentDataRequest was not constructed using fromJson(String).
        val paymentMethodData = JSONObject(paymentInformation).getJSONObject("paymentMethodData")

        // If the gateway is set to "example", no payment information is returned - instead, the
        // token will only consist of "examplePaymentMethodToken".
        if (paymentMethodData
            .getJSONObject("tokenizationData")
            .getString("type") == "PAYMENT_GATEWAY" && paymentMethodData
            .getJSONObject("tokenizationData")
            .getString("token") == "examplePaymentMethodToken"
        ) {

          Log.e(
            "handlePaymentSuccess", "Error: " + "Gateway name set to \"example\" - please modify " +
                "Constants.java and replace it with your own gateway."
          )
        }

        /*
        val billingName = paymentMethodData.getJSONObject("info").getJSONObject("billingAddress").getString("name")
        Log.d("BillingName", billingName)
        */

        val cardInfo = paymentMethodData.getJSONObject("info")
        val network = cardInfo.getString("cardNetwork")
        // Logging token string.
        val tokenJsonData = JSONObject(
            paymentMethodData
          .getJSONObject("tokenizationData")
                .getString("token")
        )
        tokenJsonData.put("cardDetails", cardInfo.getString("cardDetails"))
        val tokenData = tokenJsonData.toString()

        //Log.d("GooglePaymentToken", tokenData)

        listener.invoke(Right(PayResult(tokenData, network)))
      } catch (e: JSONException) {
        Log.e("handlePaymentSuccess", "Error: " + e.toString())
      }

    }


    /**
     * Determine the viewer's ability to pay with a payment method supported by your app and display a
     * Google Pay payment button.
     *
     * @see [](https://developers.google.com/android/reference/com/google/android/gms/wallet/PaymentsClient.html.isReadyToPay
    ) */
    private fun possiblyShowGooglePayButton(
      allowedCardNetworks: JSONArray,
      allowedAuthMethods: JSONArray,
      listener: (Either<Failure, Boolean>) -> Unit
    ) {

      val isReadyToPayJson = PaymentsUtil.isReadyToPayRequest(allowedCardNetworks, allowedAuthMethods) ?: return
      val request = IsReadyToPayRequest.fromJson(isReadyToPayJson.toString()) ?: return

      // The call to isReadyToPay is asynchronous and returns a Task. We need to provide an
      // OnCompleteListener to be triggered when the result of the call is known.
      val task = paymentsClient.isReadyToPay(request)
      task.addOnCompleteListener { completedTask ->
        try {
          completedTask.getResult(ApiException::class.java)
            ?.let { setGooglePayAvailable(it, listener) }
        } catch (exception: ApiException) {
          // Process error
          Log.w("isReadyToPay failed", exception)
        }
      }
    }


    /**
     * If isReadyToPay returned `true`, show the button and hide the "checking" text. Otherwise,
     * notify the user that Google Pay is not available. Please adjust to fit in with your current
     * user flow. You are not required to explicitly let the user know if isReadyToPay returns `false`.
     *
     * @param available isReadyToPay API response.
     */
    private fun setGooglePayAvailable(
      available: Boolean,
      listener: (Either<Failure, Boolean>) -> Unit
    ) {
      if (available) {
        if (handlePayButtonVisibility) {
          googlePayButton.visibility = View.VISIBLE
        }
        listener.invoke(Right(true))
      } else {
        Log.e("Error", "Unfortunately, Google Pay is not available on this device")
        listener.invoke(Left(NotAvailableOnThisDevice))
      }
    }
  }

  companion object{
    const val LOAD_PAYMENT_DATA_REQUEST_CODE = 991
  }
}