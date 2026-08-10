package com.google.firebase.auth;

import com.google.firebase.FirebaseException;

public class PhoneAuthProvider {
    public abstract static class OnVerificationStateChangedCallbacks {
        public void onVerificationCompleted(PhoneAuthCredential credential) {
        }

        public void onVerificationFailed(FirebaseException exception) {
        }

        public void onCodeSent(String verificationId, ForceResendingToken token) {
        }

        public void onCodeAutoRetrievalTimeOut(String verificationId) {
        }
    }

    public static class ForceResendingToken {
    }
}
