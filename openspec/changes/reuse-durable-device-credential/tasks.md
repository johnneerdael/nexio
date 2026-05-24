- [ ] Add migration support for reuse handoffs, including a nullable reference from `device_credential_handoffs` to the selected `device_credentials` row.
- [ ] Update TV login approval data so nexio-web records whether approval chose matched reuse or create-new-device.
- [ ] Add matched credential candidate query/filtering for active owner credentials by normalized device metadata.
- [ ] Update nexio-web approval UI to show only matched reuse candidates and always keep Create new device available.
- [ ] Update `tv-logins-exchange` to rotate the selected logical credential when reuse was approved.
- [ ] Update `activate_device_credential_handoff` to transactionally consume reuse handoffs and update the selected credential row.
- [ ] Add Supabase function and migration tests for match filtering, reuse rotation, stale candidate rejection, old credential rejection, and create-new compatibility.
- [ ] Verify Android QR login exchange still succeeds without response-shape changes.

