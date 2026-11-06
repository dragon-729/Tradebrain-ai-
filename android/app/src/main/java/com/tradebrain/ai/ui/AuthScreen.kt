package com.tradebrain.ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tradebrain.ai.data.*
import kotlinx.coroutines.launch

class AuthVM : ViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var register by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var isError by mutableStateOf(false)

    private fun fail(m: String) { message = m; isError = true }
    private fun info(m: String) { message = m; isError = false }

    fun submit() {
        val e = email.trim()
        if (!e.contains("@")) return fail("Enter a valid email address.")
        if (password.length < 8) return fail("Password must be at least 8 characters.")
        viewModelScope.launch {
            busy = true; message = null
            if (register) {
                call { Graph.auth.signup(Credentials(e, password)) }
                    .onSuccess { r ->
                        val at = r.access_token; val rt = r.refresh_token
                        if (at != null && rt != null) Graph.session.save(AuthSession(at, rt, r.user))
                        else { info("Account created. Check your email to verify it, then sign in."); register = false }
                    }.onFailure { fail(it.friendly()) }
            } else {
                call { Graph.auth.login(Credentials(e, password)) }
                    .onSuccess { Graph.session.save(it) }
                    .onFailure { fail(it.friendly()) }
            }
            busy = false
        }
    }

    fun reset() {
        val e = email.trim()
        if (!e.contains("@")) return fail("Enter your email first.")
        viewModelScope.launch {
            busy = true
            call { Graph.auth.recover(RecoverBody(e)) }
                .onSuccess { info("If that email has an account, a reset link has been sent.") }
                .onFailure { fail(it.friendly()) }
            busy = false
        }
    }
}

@Composable
fun AuthScreen(vm: AuthVM = viewModel()) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TradeBrain AI", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text("Chart analysis · Personal knowledge · AI assistant", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(vm.email, { vm.email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(vm.password, { vm.password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        vm.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (vm.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = vm::submit, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (vm.register) "Create account" else "Sign in")
        }
        TextButton(onClick = { vm.register = !vm.register; vm.message = null }) {
            Text(if (vm.register) "Have an account? Sign in" else "New here? Create an account")
        }
        if (!vm.register) TextButton(onClick = vm::reset, enabled = !vm.busy) { Text("Forgot password?") }
        Spacer(Modifier.height(24.dp))
        Muted("GitHub: github.com/dragon-729  ·  Telegram: @YogeshBhai729")
        Row {
            TextButton(onClick = { uri.openUri("https://github.com/dragon-729") }) { Text("GitHub") }
            TextButton(onClick = { uri.openUri("https://t.me/YogeshBhai729") }) { Text("Telegram") }
        }
    }
}
