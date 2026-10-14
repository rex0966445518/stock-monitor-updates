package com.rex.twboardingscanner

class ScannerApplication:android.app.Application(){
    override fun onCreate(){super.onCreate();com.rex.twboardingscanner.data.ScanDiagnostics.install(this)}
}
