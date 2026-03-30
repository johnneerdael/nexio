## ADDED Requirements

### Requirement: Verified email is required for email/password signup
The portal SHALL require email verification before a newly created email/password account can access authenticated portal features.

#### Scenario: Signup succeeds without an immediate session
- **WHEN** a user signs up with email and password and Supabase requires email confirmation
- **THEN** the portal signup API returns a verification-required result instead of an authenticated session
- **AND** the web UI instructs the user to check their email before signing in

#### Scenario: Unconfirmed email cannot sign in
- **WHEN** a user attempts to sign in before verifying the email address
- **THEN** the portal returns a clear verify-email error message

### Requirement: The portal can resend signup verification email
The portal SHALL allow a pending email/password signup to request another confirmation email.

#### Scenario: Resend verification email
- **WHEN** the user requests another signup confirmation message from the portal
- **THEN** the portal calls the Supabase resend confirmation flow
- **AND** the UI shows a success message without signing the user in

### Requirement: The portal supports password recovery
The portal SHALL provide password reset initiation and completion flows for web users.

#### Scenario: Request password reset email
- **WHEN** a user submits an email address through the forgot-password flow
- **THEN** the portal triggers the Supabase recovery email flow
- **AND** the response remains generic enough to avoid leaking whether the email exists

#### Scenario: Finish password recovery in the portal
- **WHEN** a user follows a valid recovery link into the Nexio reset-password page
- **THEN** the portal completes the recovery session and allows the user to set a new password

### Requirement: Signed-in users can manage portal password login
The portal SHALL let an authenticated account set or change a password from the signed-in settings surface.

#### Scenario: Google-authenticated user adds password login
- **WHEN** a signed-in Google-authenticated user sets a password from the security panel
- **THEN** the account can continue using Google sign-in
- **AND** the account can also sign in with email and password afterward

#### Scenario: Existing password user changes password
- **WHEN** a signed-in portal user updates their password from the security panel
- **THEN** the password change succeeds without leaving the portal
