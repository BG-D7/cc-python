# CC: Python Cyrillic font — credits and license

`assets/computercraft/textures/gui/term_font.png` replaces the CC: Tweaked terminal font with one that has
Cyrillic letters at their Windows-1251 (CP1251) positions: А–я = 0xC0–0xFF, Ё = 0xA8, ё = 0xB8, plus Ў ў Є є Ї ї
І і ґ №. That is the encoding CC: Python uses by default (`text.encoding = cp1251`).

- **Base font:** the CC: Tweaked / ComputerCraft terminal font, © 2011 Daniel Ratcliffe, licensed under the
  ComputerCraft Public License (CCPL, see [LICENSE-CCPL.txt](LICENSE-CCPL.txt)). 180 of the 256 glyphs are unchanged.
- **Cyrillic glyphs (76 glyphs):** by **sashafiesta**, from the resource pack
  [CCCyrillic 1.0.0](https://modrinth.com/resourcepack/cccyrillic) (published under the MIT License).

Because the file is a modified version of a CCPL asset, it is distributed under the **CCPL**: free to use in
non-commercial Minecraft mods and packs, with the same terms for redistributed copies. This font is separate from the
CC: Python mod, which is licensed under the GNU LGPL v3.
