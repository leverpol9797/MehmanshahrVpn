# Native JNI entry points are registered by HEV's JNI_OnLoad.
-keep class hev.htproxy.TProxyService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# The licence verifier.
#
# net.i2p.crypto:eddsa is a plain Java library, but it carries a reflective
# fallback into sun.security.x509 for parsing JDK-generated keys, and R8 reads
# that reference as a missing class and stops the build:
#
#   ERROR: R8: Missing class sun.security.x509.X509Key
#     (referenced from: void net.i2p.crypto.eddsa.EdDSAEngine...)
#
# There is no such class on Android and none is needed — the verifier only calls
# EdDSAEngine.verify(). So the reference is told not to fail the build, and the
# library itself is kept by name because EdDSAPrivateKey and friends are
# constructed reflectively by the verification path.
-dontwarn sun.security.**
-dontwarn net.i2p.crypto.eddsa.**
-keep class net.i2p.crypto.eddsa.** { *; }
