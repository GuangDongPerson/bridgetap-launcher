# Bridgetap - launcher

English · [简体中文](READMEzhcn.md) · [繁體中文](READMEzhtw.md)

A Plugin for bridging jumps coordinate viewing to bridgers with *Minecraft 1.8.9 (blc & lc)*  
In-game screenshot (see top-left corner):

<img width="2297" height="1247" alt="example" src="https://github.com/user-attachments/assets/0fb76ffb-70e2-48e3-8433-36fa6220d86b" />


[Download the latest version](https://github.com/GuangDongPerson/bridgetap-launcher/releases/latest) · [All releases](https://github.com/GuangDongPerson/bridgetap-launcher/releases) · [Changelog](CHANGELOG.md) · [Report bugs](https://github.com/GuangDongPerson/bridgetap-launcher/issues)

**1.0.12** developed baseed on [sgggnnn/bridgetap](https://github.com/sgggnnn/bridgetap) 1.5.0, has all the HUD and Bridge Tracking features of this version

## Installation

1. Open [release 1.0.12](https://github.com/GuangDongPerson/bridgetap-launcher/releases/tag/v1.0.12).
2. Launch *Badlion/Lunar Client* with **Java 8** or later.
3. Wait for the launcher to detect the game process.
4. Click the "launch" button or Wait for the launcher to  launch automatically (if "Auto-launch" is enabled in settings)
5. Do /bt in game process to make sure the launch takes effect.
6. Enjoy. **:D**

## Features
- **Bridge HUD**: jump coordinates, deltas, ticks, turns, finish coordinates, and a Gain / No Gain indicator.
- **Customizable display**: position, scale, background, shadow, individual element colors, chat output, and a `LAST_TURN` mode showing only the latest jump and turn coordinates.
- **Opening detection**: 3-tick 1.0 Flat，4-tick 1.05 Flat, 5-tick 1.1 Flat , 6-tick 1.15 Flat , 5b Late & 6b Late ,hold speed&tap speed
- **Bridge measurements**: the gap between platform edges and their height difference, when the recording contains the required data.

## Development

```bash
npm install          # only installs electron and electron-builder 
npm run fonts        # fetch the MiSans font shards (required on first run, needs network) 
npm start            # run from source
npm run dist         # package → dist/BridgeTap- launcher-1.0.12.exe 
```

`npm run dist` first checks that the fonts are complete; if any are missing it fails outright instead of producing a build without fonts.

## Fonts: why they are not distributed with the repository

The UI uses MiSans (Xiaomi). Article 3 of the "MiSans Font Intellectual Property License Agreement" forbids "further distributing the font software or any copies thereof", and committing the 56 woff2 files into a public repository is exactly that act.

So:

- The repository has **only** `src/renderer/styles/fonts.css` (56 `@font-face` rules + unicode-range) and `src/renderer/assets/fonts/MiSans-license.txt`; the font files themselves are not there. 
- Before building, run `npm run fonts` to fetch the shards from the npm package `misans@5.0.0`, at the same paths as in `fonts.css`.
- Shipping them inside the exe is **embedded use**, which the agreement explicitly allows (FAQ: "may be used as an embedded font"), provided that MiSans is credited in the software — the line at the bottom of the "About" page does that.

## Areas for Improvement ##

- Incorporate features from the latest upstream version
- Add several new functionalities

## Credits

Maintained at [GuangDongPerson/bridgetap-launcher](https://github.com/GuangDongPerson/bridgetap-launcher). Thanks to the project [bridgetap](https://github.com/sgggnnn/bridgetap).

## License

The application itself: GPL-3.0, see [LICENSE](LICENSE).

Third-party components shipped with the artifacts (Electron, ASM, MiSans) and their license texts are listed in `THIRD-PARTY.md` at the repository root; the full texts live in `licenses/`.

UI font MiSans: Copyright (c) Beijing Xiaomi Mobile Software Co., Ltd., distributed under the "MiSans Font Intellectual Property License Agreement", shipped with the artifacts in embedded form only, with no modification to the glyphs. The full agreement and the boundaries of use are in `src/renderer/assets/fonts/MiSans-license.txt`.

## Discussion Group
[Discord](https://discord.gg/Dm7YjP9hGU) · [QQ](https://qm.qq.com/q/PkzlxqrUce)

## Author wants to say
I was blown away when I first came across this mod in the community. Previously, for those of us bridgers in Minecraft, checking our coordinates(mm) meant constantly re-watching replays and manually adjusting our positioning. The original mod only supported Fabric at the time (though Forge support was added later). I wondered if, by leveraging recent advancements in AI—using a "vibe coding" approach—I could create a modified version compatible with Badlion and Lunar Client. I began my research on the evening of November 9, 2026. Once I realized it was actually feasible, I couldn't stop. As a first-year university student, I would dive straight into the project the moment I returned to my dorm after class—tackling everything from rendering the bridging data and hooking into game mechanics to building a polished UI for an external  launcher, alongside countless bug fixes and optimizations. It took about a week in total (finishing on November 17, 2026). 

The plugin has reached a functional state, though there are still some minor issues that need improvement. Please feel free to share your valuable feedback—but before that, I’m going to take a week off; I’ve been grinding so hard on this project over the past week that I’ve barely had any proper sleep...

Attachment: Token Usage

<img width="2560" height="1440" alt="tokens en" src="https://github.com/user-attachments/assets/b2d91b6f-0337-4cb6-9c78-7caa7f471873" />


