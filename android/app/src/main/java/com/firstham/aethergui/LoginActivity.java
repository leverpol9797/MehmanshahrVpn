package com.firstham.aethergui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.TextInputEditText;

import com.firstham.aethergui.databinding.ActivityLoginBinding;

/**
 * The launcher. Holds the gate shut until a license signed by the issuing
 * service is presented.
 *
 * This screen is a convenience, not the enforcement point. The quick-settings
 * tile and the home-screen widget start the tunnel service without ever opening
 * an activity, so {@link AetherVpnService} checks {@link AuthGate} for itself.
 * A check here alone would leave the tunnel startable.
 */
public final class LoginActivity extends AppCompatActivity {

    private ActivityLoginBinding binding;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // A valid license means there is nothing to ask.
        if (AuthGate.isValid(this)) {
            openMain();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // The scroller carries android:fitsSystemWindows, so the system bars are
        // handled by the framework instead of by hand here.

        if (!LicenseVerifier.isKeyConfigured()) {
            // A build shipped with the placeholder key. No license it is shown
            // can ever verify, so the button stays disabled — but the reason is
            // stated at the top of the screen, not in small text under it.
            binding.buildErrorBox.setVisibility(View.VISIBLE);
            binding.loginButton.setEnabled(false);
            showStatus(getString(R.string.login_key_missing), true);
        }

        binding.loginButton.setOnClickListener(v -> submit());
        binding.pasteButton.setOnClickListener(v -> pasteInto());
        binding.deviceCopyButton.setOnClickListener(v -> copyRequest());
        binding.botCopyButton.setOnClickListener(v -> copyBotId());
        binding.botOpenButton.setOnClickListener(v -> openRubika());

        showDeviceCode();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                finishAffinity();
            }
        });
    }

    private void showDeviceCode() {
        binding.deviceCodeText.setText(DeviceId.get(this));
    }

    /**
     * Copies the device code.
     *
     * The app cannot read the handset's own number without a permission the
     * user would rightly refuse, so the code is copied on its own and the
     * customer sends it along with their number. The bot accepts either: a
     * message carrying both, or the code alone.
     */
    private void copyRequest() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("device", DeviceId.get(this)));
            showStatus(getString(R.string.device_copied), false);
        }
    }

    /**
     * Copies the bot id so it can be pasted into Rubika's search box.
     *
     * Rubika addresses bots as name@ (the @ trails), so the string copied here
     * matches what the user sees in the app.
     */
    private void copyBotId() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("bot", getString(R.string.license_bot_id)));
            showStatus(getString(R.string.license_bot_copied), false);
        }
    }

    private void openRubika() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.license_bot_link))));
        } catch (ActivityNotFoundException e) {
            showStatus(getString(R.string.login_bot_no_app), true);
        }
    }

    private void pasteInto() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()
                || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) {
            showStatus(getString(R.string.login_clipboard_empty), true);
            return;
        }
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (text != null) binding.passwordInput.setText(text);
    }

    private void submit() {
        TextInputEditText field = binding.passwordInput;
        String typed = field.getText() == null ? "" : field.getText().toString().trim();
        if (typed.isEmpty()) {
            binding.passwordLayout.setError(getString(R.string.login_empty));
            return;
        }
        binding.passwordLayout.setError(null);

        LicenseVerifier.Result result = AuthGate.submit(this, typed);

        if (result.isValid()) {
            openMain();
            return;
        }

        binding.passwordLayout.setError(messageFor(result));
        showStatus(messageFor(result), true);
    }

    private String messageFor(LicenseVerifier.Result result) {
        switch (result.status) {
            case EXPIRED:
                return getString(R.string.login_expired);
            case NOT_YET_VALID:
                // Almost always a wrong device clock.
                return getString(R.string.login_not_yet_valid);
            case MALFORMED:
                return getString(R.string.login_malformed);
            case WRONG_DEVICE:
                // The licence is genuine, just not for this handset. Reporting
                // it as "not valid" reads as a cracked or expired licence and
                // sends the customer off to ask for a refund.
                return getString(R.string.login_wrong_device);
            case BAD_SIGNATURE:
            default:
                return getString(R.string.login_wrong_password);
        }
    }

    private void showStatus(String text, boolean isError) {
        TextView status = binding.loginStatus;
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        status.setTextColor(ContextCompat.getColor(this,
                isError ? R.color.red : R.color.muted));
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onResume() {
        super.onResume();
        // Returning from the main screen after a lapse, or after a licence was
        // cleared, should land back here rather than on a dead Connect button.
        if (binding != null && AuthGate.isValid(this)) openMain();
    }
}
