# Todd stable signing

Todd's permanent Android signing certificate SHA-256 is:

`2354bcf2cbc13c549948898626cbc45509382b6f9b9e246d76355cce4d9e0d92`

The private keystore must never be committed to this repository.

The stable release workflow requires these GitHub Actions repository secrets:

- `TODD_KEYSTORE_BASE64`
- `TODD_SIGNING_STORE_PASSWORD`
- `TODD_SIGNING_KEY_ALIAS`
- `TODD_SIGNING_KEY_PASSWORD`

After the secrets are configured, run **Todd Stable Release APK** manually. The workflow refuses to publish an APK unless its signing certificate exactly matches the fingerprint above.

The first APK installed with this certificate becomes the permanent update baseline. All later distributed Todd APKs must use the same private key.
