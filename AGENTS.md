# Public source repository

This repository is public. Keep application source, required build instructions,
dependency licenses, and user-facing release notes here.

- Do not commit internal infrastructure diagrams, deployment instructions,
  white-label audits, server inventories, operational incident notes, customer
  data, account identifiers, private testing evidence, or credentials.
- Keep internal working notes outside this repository, including untracked notes
  that might later be staged accidentally. Do not copy them into release notes.
- Never commit signing material, service credentials, review-account passwords,
  local environment files, device logs, or production API responses.
- Inspect the complete staged diff and run `bash scripts/check-public-docs.sh`
  before publishing. Stage explicit paths, not the entire working tree.
- Preserve all upstream source, license notices, and corresponding-source build
  requirements. Removing private operational notes must not remove app code.
- A deletion commit does not erase old Git history. History rewrites, release-tag
  changes, and force pushes require separate user authorization.
