# Contributing to PettiBox

Thanks for helping improve PettiBox. Bug reports, translations, and code changes are all welcome.
Everyone taking part is expected to follow the [code of conduct](CODE_OF_CONDUCT.md).

## Reporting a bug

Open an issue with:

- What you did, what you expected, and what happened instead
- Your phone model, Android version, and PettiBox version (Android Settings > Apps > PettiBox)
- What you were saving (link, text, image, PDF) and which app you shared it from

## Suggesting a feature

Open an issue describing the problem you want solved before writing code, so we can agree on
the approach first.

## Translations

Strings live in `res/values/strings.xml` (English) and `res/values-<language>/strings.xml`.
To add a language, copy the English file into a new `values-<code>` folder and translate the
values, keeping the `name` attributes and any `%1$s`-style placeholders unchanged.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Keep each pull request to one change, and explain what it does and why.
3. Match the style of the surrounding code (Kotlin, Jetpack Compose).
4. Run the unit tests and make sure they pass:

   ```bash
   ./gradlew :app:testDebugUnitTest
   ```

5. For changes to sharing, OCR, backups or reminders, test on a real device and say which device and Android version you used.
6. Add a line under "Unreleased" in [CHANGELOG.md](CHANGELOG.md) for anything users will
   notice, and update the README or architecture docs if you change how the app is
   structured, its permissions, or its build steps.

CI runs the unit tests and a debug build on every pull request.

Do not commit build output, keystores, `local.properties`, `keystore.properties`, or IDE
settings; `.gitignore` already excludes them.

## License of contributions

By submitting a pull request, you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the same license as the project.
