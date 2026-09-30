# Privacy Policy for OpenWhispr

OpenWhispr is an Android dictation app that records speech, transcribes it, and inserts the result into text fields across apps.

## Data handling

OpenWhispr supports two transcription modes.

### Local mode

In local mode, audio is processed on-device using local speech recognition models. Audio does not leave the device.

### Cloud mode

In cloud mode, recorded audio is sent directly from the device to Groq's transcription API to generate text.

If optional cleanup is enabled, the transcribed text is sent directly from the device to the selected cleanup provider's OpenAI-compatible chat completions API. Cleanup uses Groq by default; you can instead configure another HTTPS-compatible endpoint, such as OpenRouter or DeepSeek. Speech transcription remains on Groq Whisper Large V3 whenever cloud transcription is selected.

## API keys

Groq and optional cleanup-provider API keys are encrypted before storage in private app preferences using a non-exportable AES key held by the Android Keystore. Existing Groq keys from older app versions are migrated to encrypted storage on first access and then removed from their legacy preference. Keys are used only to authenticate direct requests to their respective providers; the app does not log them or send them to an OpenWispr server.

I do not operate a relay server for these requests.

## Accessibility Service

OpenWhispr uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

OpenWhispr is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for OpenWhispr and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as Groq, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, open an issue at: https://github.com/EdiBianco/OpenWhispr
