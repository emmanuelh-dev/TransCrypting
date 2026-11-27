# TransCrypting para Android

Grabadora nativa con transcripción local en español mediante Nemotron Labs Unified ASR 0.6B.

## Uso

1. Instala la APK en un dispositivo ARM64 con Android 8.0 o posterior.
2. Pulsa **Descargar modelo**. La descarga inicial requiere internet y ocupa aproximadamente 672 MB.
3. Pulsa **Grabar con micrófono** y concede el permiso de Android. Si sales de la app, se detiene y guarda la grabación.
4. Pulsa **Detener y guardar**. La transcripción empieza automáticamente si el modelo está instalado.
5. Abre **Grabaciones** para reproducir audios anteriores, transcribir pendientes o copiar el texto.

La app guarda el audio WAV y las transcripciones en su almacenamiento privado. Después de descargar el modelo funciona sin conexión. Esta versión usa CPU y genera el texto al terminar la grabación.

## Compilar

Se requiere Java 17 y Android SDK 36. Desde esta carpeta:

```sh
./gradlew assembleDebug lintDebug
```

La APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

## Modelo y runtime

- [NVIDIA Nemotron Labs Unified ASR 0.6B](https://huggingface.co/nvidia/nemotron-labs-unified-asr-0.6b) bajo OpenMDW 1.1.
- [Conversión ONNX INT8 de Pisar](https://huggingface.co/pisar/nemotron-unified-asr-0.6b-sherpa-onnx/tree/7691099b36bdb2978117a78834442435e55d59f3), descargada con verificación SHA-256 y configurada para español.
- [sherpa-onnx 2.0.3](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.3) con ONNX Runtime. Las licencias se incluyen en `app/src/main/assets/licenses/`.
