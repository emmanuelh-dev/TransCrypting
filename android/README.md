# TransCrypting para Android

Grabadora nativa con transcripción local en español mediante Nemotron Labs Unified ASR 0.6B.

## Uso

1. Instala la APK en un dispositivo ARM64 con Android 8.0 o posterior.
2. Abre **Recursos** y pulsa **Descargar modelo**. La descarga inicial requiere internet y ocupa aproximadamente 672 MB. Puedes grabar antes de descargarlo.
3. Pulsa **Grabar con micrófono** y concede el permiso de micrófono. En la pantalla de grabación puedes ver el tiempo y el nivel del micrófono, pausar y reanudar. Si la app pasa a segundo plano, se detiene y guarda la grabación.
4. Pulsa **Guardar** para abrir el detalle. La transcripción empieza automáticamente si el modelo está instalado.
5. Abre una grabación de la biblioteca y cambia entre **Audio**, **Transcripción** y **Chat** desde el detalle. Puedes ver la forma de onda, reproducir el audio, copiar la transcripción o eliminar la grabación.
6. Usa **Importar audio** en la biblioteca o elige **TransCrypting** en el menú **Compartir** de Android. Al terminar la importación puedes escuchar el audio mientras se transcribe.
7. Para personalizar el color de acento, abre **Tema** desde la biblioteca.

La app guarda el audio WAV y las transcripciones en su almacenamiento privado. Después de descargar el modelo puedes grabar y transcribir sin conexión. Esta versión usa CPU y genera el texto al terminar la grabación.

Para hacer preguntas sobre una grabación transcrita, abre **Chat** en el detalle. Puedes guardar tu clave de DeepSeek desde **Clave** o **Recursos**. El chat usa DeepSeek Flash por internet y muestra las respuestas con Markdown conforme llegan. Al enviar una pregunta, se comparte el texto de esa transcripción y el historial del chat con DeepSeek.

Al terminar, la pantalla muestra la duración del audio, el tiempo total de transcripción y cuánto de ese tiempo tomó cargar Nemotron. Usa **Repetir** en una grabación para volver a medir con el mismo audio.

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
- Iconos de [Lucide](https://lucide.dev/) en formato VectorDrawable.
- Markdown con [Markwon](https://github.com/noties/Markwon) y [commonmark-java](https://github.com/commonmark/commonmark-java).
