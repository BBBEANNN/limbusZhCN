package com.google.firebase.auth;

import com.google.firebase.FirebaseApp;

import java.util.ArrayList;
import java.util.List;

public final class FirebaseAuth {
    private static final FirebaseAuth INSTANCE = new FirebaseAuth();

    private final List<AuthStateListener> authStateListeners = new ArrayList<>();
    private final List<IdTokenListener> idTokenListeners = new ArrayList<>();
    private String languageCode;

    public static FirebaseAuth getInstance() {
        return INSTANCE;
    }

    public static FirebaseAuth getInstance(FirebaseApp firebaseApp) {
        return INSTANCE;
    }

    public FirebaseUser getCurrentUser() {
        return null;
    }

    public void addAuthStateListener(AuthStateListener listener) {
        if (listener == null) {
            return;
        }
        authStateListeners.add(listener);
        notifyAuthStateChanged(listener);
    }

    public void removeAuthStateListener(AuthStateListener listener) {
        authStateListeners.remove(listener);
    }

    public void addIdTokenListener(IdTokenListener listener) {
        if (listener == null) {
            return;
        }
        idTokenListeners.add(listener);
        notifyIdTokenChanged(listener);
    }

    public void removeIdTokenListener(IdTokenListener listener) {
        idTokenListeners.remove(listener);
    }

    public void signOut() {
        for (AuthStateListener listener : new ArrayList<>(authStateListeners)) {
            notifyAuthStateChanged(listener);
        }
        for (IdTokenListener listener : new ArrayList<>(idTokenListeners)) {
            notifyIdTokenChanged(listener);
        }
    }

    public void setLanguageCode(String languageCode) {
        this.languageCode = languageCode;
    }

    public String getLanguageCode() {
        return languageCode;
    }

    public void useAppLanguage() {
        languageCode = "en-US";
    }

    private void notifyAuthStateChanged(final AuthStateListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                listener.onAuthStateChanged(FirebaseAuth.this);
            }
        }, "LimbusFirebaseAuthState").start();
    }

    private void notifyIdTokenChanged(final IdTokenListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                listener.onIdTokenChanged(FirebaseAuth.this);
            }
        }, "LimbusFirebaseIdToken").start();
    }

    public interface AuthStateListener {
        void onAuthStateChanged(FirebaseAuth auth);
    }

    public interface IdTokenListener {
        void onIdTokenChanged(FirebaseAuth auth);
    }
}
