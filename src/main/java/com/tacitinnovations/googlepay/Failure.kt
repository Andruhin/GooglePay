package com.tacitinnovations.googlepay

sealed class Failure {
  object NotAvailableOnThisDevice : Failure()
  object LoadPaymentDataFailed : Failure()
}