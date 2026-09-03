# Third-party notices

## cocos2d-x 3.14.1 (MIT)

`app/src/main/java/org/cocos2dx/lib/` contains the Android Java glue classes
from cocos2d-x 3.14.1 (https://github.com/cocos2d/cocos2d-x, tag
`cocos2d-x-3.14.1`, `cocos/platform/android/java`), with local modifications
described in `NOTES.md` ("What's vendored / modified"). Most files carry the
upstream header; the ones that do not (Cocos2dxDownloader, Cocos2dxWebView,
Cocos2dxWebViewHelper, GameController*) are from the same tree and under the
same terms:

    Copyright (c) 2010-2013 cocos2d-x.org
    Copyright (c) 2013-2016 Chukong Technologies Inc.

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
    FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.

## android-async-http 1.4.9 (Apache License 2.0)

Fetched by Gradle (`com.loopj.android:android-async-http`), required only
because the game engine looks up `Cocos2dxDownloader` at startup.

## Not included

Chrono Trigger, its Android application, native library, and assets are the
property of Square Enix; the Nintendo DS release is likewise not included.
ChronoDuo loads the official Android game the user has installed and, if the
user supplies their own DS ROM, decodes map images from it on the device.
Nothing derived from either is distributed with this project.
