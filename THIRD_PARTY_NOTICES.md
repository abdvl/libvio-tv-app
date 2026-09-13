# Third-party software

LIBVIO TV application code is licensed under MIT. Third-party components retain their own licenses.

## libVLC Android 3.6.5

- Copyright: VLC authors, VideoLAN and VideoLabs.
- Maven dependency: `org.videolan.android:libvlc-all:3.6.5` (unmodified).
- License declared by the publisher: GNU Lesser General Public License 2.1. Individual source files allow version 2.1 or later.
- License text bundled in the APK: `assets/licenses/libvlc-LGPL-2.1.txt`.
- Android bindings and build scripts: https://code.videolan.org/videolan/libvlcjni
- VLC native engine source: https://code.videolan.org/videolan/vlc
- Exact published Android source archive: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/libvlc-all-3.6.5-sources.jar
- Binary and publisher metadata: https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/

The application dynamically loads the unmodified shared libraries from this AAR. The complete app sources and Gradle configuration are available in this repository; developers can replace the dependency with a modified libVLC build and rebuild the application. The Java source archive above covers the Android bindings; the native engine and native dependency build sources are maintained in the linked upstream repositories.
