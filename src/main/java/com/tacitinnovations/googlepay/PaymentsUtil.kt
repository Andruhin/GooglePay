package com.tacitinnovations.googlepay

import android.app.Activity
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode


object PaymentsUtil {
  val MICROS = BigDecimal(1000000.0)
  /**
   * Create a Google Pay API base request object with properties used in all requests.
   *
   * @return Google Pay API base request object.
   * @throws JSONException
   */
  private val baseRequest = JSONObject().apply {
    put("apiVersion", 2)
    put("apiVersionMinor", 0)
  }

  /**
   * Card networks supported by your app and your gateway.
   *
   *
   *
   * @return Allowed card networks
   * @see [CardParameters](https://developers.google.com/pay/api/android/reference/object.CardParameters)
   */
  private val allowedCardNetworks = JSONArray(Constants.DEFAULT_SUPPORTED_NETWORKS)

  /**
   * Card authentication methods supported by your app and your gateway.
   *
   *
   * and make updates in Constants.java.
   *
   * @return Allowed card authentication methods.
   * @see [CardParameters](https://developers.google.com/pay/api/android/reference/object.CardParameters)
   */
  private val allowedCardAuthMethods = JSONArray(Constants.SUPPORTED_METHODS)

  /**
   * Information about the merchant requesting payment information
   *
   * @return Information about the merchant.
   * @throws JSONException
   * @see [MerchantInfo](https://developers.google.com/pay/api/android/reference/object.MerchantInfo)
   */
  private val merchantInfo: JSONObject
    @Throws(JSONException::class)
    get() = JSONObject().put("merchantName", "Example Merchant")

/**
   * Describe your app's support for the CARD payment method.
   *
   *
   * The provided properties are applicable to both an IsReadyToPayRequest and a
   * PaymentDataRequest.
   *
   * @return A CARD PaymentMethod object describing accepted cards.
   * @throws JSONException
   * @see [PaymentMethod](https://developers.google.com/pay/api/android/reference/object.PaymentMethod)
   */
  // Optionally, you can add billing address/phone number associated with a CARD payment method.
  private fun baseCardPaymentMethod(allowedNetworks: JSONArray = allowedCardNetworks, allowedAuthMethods: JSONArray = allowedCardAuthMethods): JSONObject {
    return JSONObject().apply {

      val parameters = JSONObject().apply {
        put("allowedAuthMethods", allowedAuthMethods)
        put("allowedCardNetworks", allowedNetworks)
        put("billingAddressRequired", false)
        put("billingAddressParameters", JSONObject().apply {
          put("format", "FULL")
        })
      }

      put("type", "CARD")
      put("parameters", parameters)
    }
  }


  /**
   * An object describing accepted forms of payment by your app, used to determine a viewer's
   * readiness to pay.
   *
   * @return API version and payment methods supported by the app.
   * @see [IsReadyToPayRequest](https://developers.google.com/pay/api/android/reference/object.IsReadyToPayRequest)
   */
  fun isReadyToPayRequest(allowedNetworks: JSONArray = allowedCardNetworks, allowedAuthMethods: JSONArray = allowedCardAuthMethods): JSONObject? {
    return try {
      val isReadyToPayRequest = JSONObject(baseRequest.toString())
      isReadyToPayRequest.put(
        "allowedPaymentMethods", JSONArray().put(baseCardPaymentMethod(allowedNetworks, allowedAuthMethods))
      )

      isReadyToPayRequest

    } catch (e: JSONException) {
      null
    }
  }


  /**
   * An object describing information requested in a Google Pay payment sheet
   *
   * @return Payment data expected by your app.
   * @see [PaymentDataRequest](https://developers.google.com/pay/api/android/reference/object.PaymentDataRequest)
   */
  fun getPaymentDataRequest(
          price: String,
          allowedNetworks: JSONArray = allowedCardNetworks,
          allowedAuthMethods: JSONArray = allowedCardAuthMethods,
          currencyCode: String = Constants.DEFAULT_CURRENCY_CODE,
          paymentGateWayTokenization: JSONObject = JSONObject(Constants.DEFAULT_PAYMENT_GATEWAY_TOKENIZATION_PARAMETERS),
          merchantName: String
  ): JSONObject? {
    try {
      return JSONObject(baseRequest.toString()).apply {
        put(
          "allowedPaymentMethods",
          JSONArray().put(cardPaymentMethod(allowedNetworks, allowedAuthMethods, paymentGateWayTokenization))
        )
        put("transactionInfo", getTransactionInfo(price, currencyCode))
          put("merchantInfo", JSONObject().put("merchantName", merchantName))
        /*
        // An optional shipping address requirement is a top-level property of the
        // PaymentDataRequest JSON object.
        val shippingAddressParameters = JSONObject().apply {
          put("phoneNumberRequired", false)
          put("allowedCountryCodes", JSONArray(Constants.SHIPPING_SUPPORTED_COUNTRIES))
        }
        put("shippingAddressRequired", true)
        put("shippingAddressParameters", shippingAddressParameters)*/
      }
    } catch (e: JSONException) {
      return null
    }
  }

  /**
   * Describe the expected returned payment data for the CARD payment method
   *
   * @return A CARD PaymentMethod describing accepted cards and optional fields.
   * @throws JSONException
   * @see [PaymentMethod](https://developers.google.com/pay/api/android/reference/object.PaymentMethod)
   */
  private fun cardPaymentMethod(
          allowedNetworks: JSONArray = allowedCardNetworks,
          allowedAuthMethods: JSONArray = allowedCardAuthMethods,
          paymentGateWayTokenization: JSONObject = JSONObject(Constants.DEFAULT_PAYMENT_GATEWAY_TOKENIZATION_PARAMETERS)
  ): JSONObject {
    val cardPaymentMethod = baseCardPaymentMethod(allowedNetworks, allowedAuthMethods)
    cardPaymentMethod.put(
      "tokenizationSpecification",
      gatewayTokenizationSpecification(paymentGateWayTokenization)
    )

    return cardPaymentMethod
  }


  /**
   * Creates an instance of [PaymentsClient] for use in an [Activity] using the
   * environment and theme set in [Constants].
   *
   * @param activity is the caller's activity.
   */
  fun createPaymentsClient(activity: Activity, environment: Int): PaymentsClient {
    val walletOptions = Wallet.WalletOptions.Builder()
      .setEnvironment(environment)
      .build()

    return Wallet.getPaymentsClient(activity, walletOptions)
  }


  /**
   * Provide Google Pay API with a payment amount, currency, and amount status.
   *
   * @return information about the requested payment.
   * @throws JSONException
   * @see [TransactionInfo](https://developers.google.com/pay/api/android/reference/object.TransactionInfo)
   */
  @Throws(JSONException::class)
  private fun getTransactionInfo(
    price: String,
    currencyCode: String = Constants.DEFAULT_CURRENCY_CODE
  ): JSONObject {
    return JSONObject().apply {
      put("totalPrice", price)
      put("totalPriceStatus", "FINAL")
      put("currencyCode", currencyCode)
    }
  }


  /**
   * Gateway Integration: Identify your gateway and your app's gateway merchant identifier.
   *
   *
   * The Google Pay API response will return an encrypted payment method capable of being charged
   * by a supported gateway after payer authorization.
   *
   *
   * @return Payment data tokenization for the CARD payment method.
   * @throws JSONException
   * @see [PaymentMethodTokenizationSpecification](https://developers.google.com/pay/api/android/reference/object.PaymentMethodTokenizationSpecification)
   */
  private fun gatewayTokenizationSpecification(
    paymentGateWayTokenization: JSONObject = JSONObject(
            Constants.DEFAULT_PAYMENT_GATEWAY_TOKENIZATION_PARAMETERS
    )
  ): JSONObject {
      if (Constants.DEFAULT_PAYMENT_GATEWAY_TOKENIZATION_PARAMETERS.isEmpty()) {
      throw RuntimeException(
        "Please edit the Constants.java file to add gateway name and other " +
            "parameters your processor requires"
      )
    }

    return JSONObject().apply {
      put("type", "PAYMENT_GATEWAY")
      put("parameters", paymentGateWayTokenization)
    }
  }
}

/**
 * Converts micros to a string format accepted by [PaymentsUtil.getPaymentDataRequest].
 *
 * @param micros value of the price.
 */
fun Long.microsToString() = BigDecimal(this)
  .divide(PaymentsUtil.MICROS)
  .setScale(2, RoundingMode.HALF_EVEN)
  .toString()
