## Change

<!-- Describe the behavior and affected phase. -->

## Verification

- [ ] `./mvnw -B -ntp verify`
- [ ] `npm ci && npm run build` (when UI changes)
- [ ] Security and API contracts updated when applicable

## Review checklist

- [ ] No secrets, tokens, card data, or generated credentials are committed
- [ ] Admin authorization and direct-service access are covered
- [ ] Database ownership and migration boundaries are preserved
- [ ] Failure and retry behavior is documented
