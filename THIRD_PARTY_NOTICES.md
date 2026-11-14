# Third-Party Notices

This project includes the following third-party components.

## Apache License, Version 2.0

The following components are licensed under the Apache License, Version 2.0:

### Ktor

- Repository: https://github.com/ktorio/ktor
- Purpose: HTTP server engine (CIO) serving share pages and downloads.
- Notice:
  ```
  Copyright 123011 CJK Software, All rights reserved.

  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
  except in compliance with the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software distributed under the
  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  either express or implied. See the License for the specific language governing permissions
  and limitations under the License.
  ```

### Cloudflare cloudflared

- Repository: https://github.com/cloudflare/cloudflared
- Purpose: Exposes the local HTTP server through Cloudflare Tunnel.
- Version pinned in `scripts/fetch-cloudflared.sh` / `scripts/build-cloudflared-patched.sh`.
- Pocket Share builds the pinned `2026.10.7` source with
  `scripts/cloudflared-dns.patch` so Android's DNS servers are passed to Go's resolver. The
  patch and reproducible build script are part of this repository; the upstream source is
  available at the repository above.
- Notice:
  ```
  Copyright 2023 Cloudflare, Inc.

  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
  except in compliance with the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software distributed under the
  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  either express or implied. See the License for the specific language governing permissions
  and limitations under the License.
  ```

### Apache POI test documents

- Repository: https://github.com/apache/poi
- Purpose: sample `.doc`, `.xls`, and `.ppt` files under `server/src/test/resources/samples/`
  used to regression-test legacy document previews.
- Notice:
  ```
  Apache POI
  Copyright The Apache Software Foundation

  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
  except in compliance with the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software distributed under the
  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  either express or implied. See the License for the specific language governing permissions
  and limitations under the License.
  ```

## Other components

This notice covers software components from the AndroidX, Kotlin, and Gradle ecosystems that are
included via the pinned version catalog. Those components are covered by their own licenses
(primarily Apache-2.0 or permissive BSD-style licenses), except for parts of the Android SDK
which are licensed under separate terms provided with the SDK.

PocketShare itself is licensed under Apache-2.0.
