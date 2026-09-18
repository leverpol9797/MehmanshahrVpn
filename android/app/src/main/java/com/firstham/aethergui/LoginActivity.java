package com.firstham.aethergui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.firstham.aethergui.databinding.ActivityLoginBinding;

public final class LoginActivity extends AppCompatActivity {
    private static final String PASSWORD = "09372550259";
    private static final String PREFS = "aether";
    private static final String UNLOCKED = "login_unlocked";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(UNLOCKED, false)) {
            openMain();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ActivityLoginBinding binding = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        binding.passwordInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        binding.loginButton.setOnClickListener(v -> {
            String entered = binding.passwordInput.getText() == null
                    ? "" : binding.passwordInput.getText().toString().trim();
            if (PASSWORD.equals(entered)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(UNLOCKED, true).apply();
                openMain();
            } else {
                binding.passwordLayout.setError(getString(R.string.login_wrong_password));
                binding.passwordInput.requestFocus();
                Toast.makeText(this, R.string.login_wrong_password, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override public void onBackPressed() {
        finishAffinity();
    }
}
