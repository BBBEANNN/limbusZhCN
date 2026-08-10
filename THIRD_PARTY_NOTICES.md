# Third-Party Notices

This repository combines original project code with third-party source code,
binary components, fonts, and artwork. The root `GPL-3.0` license applies only
to material for which the project authors have the right to grant that license.
Third-party material remains under its own license or copyright terms.

## VirtualApp

- Location: `third_party/virtualapp-upstream/`
- Source: <https://github.com/ServenScorpion/VirtualApp>
- Pinned revision: `ae7c4275096b6614fa1aa7befe326630fd3f8833`
- Import record: `third_party/virtualapp-upstream/UPSTREAM.txt`

The pinned upstream revision does not contain a repository-level license file.
Some individual files contain Apache-2.0, MIT, Android Open Source Project, or
other copyright/license headers, which remain in force. The root GPL license
does not purport to relicense files for which the repository owner does not own
the copyright. Downstream users are responsible for independently determining
whether their use and redistribution of this component are permitted.

## microG Services and Companion / FakeStore

- Source: <https://github.com/microg/GmsCore>
- License: Apache License 2.0
- Component versions and hashes: `app/src/main/assets/microg/NOTICE.txt`
- License text: `app/src/main/assets/licenses/microG-Apache-2.0.txt`

The APK files are bundled so a fresh container can bootstrap the Google-service
compatibility layer without downloading executable code at first launch.

## Chinese localization font

- Runtime asset: `app/src/main/assets/runtime/ChineseFont.ttf`
- Source project: <https://github.com/LocalizeLimbusCompany/LocalizeLimbusCompany>
- Source-project license copy:
  `app/src/main/assets/licenses/LocalizeLimbusCompany-CC-BY-NC-SA-4.0.txt`
- Base font: Sarasa Gothic
- Sarasa Gothic license: SIL Open Font License 1.1
- OFL text: `app/src/main/assets/licenses/Sarasa-Gothic-OFL-1.1.txt`

The font remains subject to the applicable font and source-project terms and is
not relicensed under GPL-3.0.

## Native hook and Android-derived files

The vendored runtime contains Android Open Source Project-derived files and
native hook implementations whose Apache-2.0, MIT, or other notices are kept in
their original source headers. Those headers control the corresponding files.

## Game names and artwork

Limbus Company, Project Moon, related names, characters, and artwork belong to
their respective rights holders. This is an unofficial community project and is
not endorsed by or affiliated with Project Moon. No ownership of third-party
trademarks or artwork is claimed.
