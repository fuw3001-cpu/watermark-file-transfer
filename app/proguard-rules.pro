# JNI names, native fields and callbacks are part of the shared library ABI.
-keep class org.libreoffice.kit.** { *; }
-keep class com.googlecode.tesseract.android.** { *; }
-keep class ai.onnxruntime.** { *; }
# Password-protected/encrypted PDFs and signature validation are unsupported.
# Their optional crypto providers are intentionally excluded from the package.
-dontwarn org.bouncycastle.**

# PDFBox's optional JPEG-2000 plugin is not bundled (also absent in earlier APKs).
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder

# OpenCV native bindings access Mat fields and Java wrappers by name.
-keep class org.opencv.** { *; }
