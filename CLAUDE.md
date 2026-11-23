# Loyiha qoidalari

- Minecraft 1.21.11 Fabric client mod (Yarn mappings). Qismlar: `uz.spearlunger` (Auto Lunge, anti-cheat, ClickGUI `uz.spearlunger.gui` — yangi modul shu yerda `Modules`ga qo'shiladi, umumiy pearl-ballistika `uz.spearlunger.PearlHelper`), `uz.autotnt` (Auto TNT Cart),
  `uz.autoelytra` (Auto Elytra), `uz.autocobweb` (Auto Cobweb), `uz.autobridge` (Auto Bridge), `uz.autopotion` (Auto Potion), `uz.bowaim` (Bow Aim), `uz.autototem` (Auto Totem), `uz.fastplace` (Fast Place),
  `uz.anchorguard` (Anchor Guard), `uz.cheststealer` (Chest Stealer), `uz.nametags` (Nametags+), `uz.fallcombo` (Fall Combo), `uz.autoclutch` (Auto Clutch), `uz.autopvp` (Auto PvP — to'liq avtomatik jang: yerda qilich/bolta/nayza/mace, elytra bilan uchib-sho'ng'ish, ovqat va totem o'zi; eski "Elytra PvP" shu modulga birlashtirilgan), `uz.norender` (NoRender), `uz.pearltracker` (Pearl Tracker), `uz.pearlboost` (Pearl Boost + Pearl Chase), `uz.automace` (Auto Mace), `uz.autotravel` (Auto Travel, /goto), `uz.automine` (Auto Mine, /mine — belgilangan hudud ichida ruda qazish, sotish, keraksizni tashlash), `uz.crystalhud` (Crystal HUD); elytra/firework yordamchilari `uz.spearlunger.ElytraHelper`.
  Inventar <-> hotbar almashtirish faqat `uz.spearlunger.InvHelper.swap` orqali (inventarni ochib-yopadi).
  Mixin'lar: `uz.spearlunger.mixin` (`spearlunger.mixins.json`); render mixin'lari `require = 0` (nom mos kelmasa crash bo'lmasin).
- Egasi bilan kelishuv: ish tugagach PR ochiladi va CI (`build` workflow) yashil bo'lsa, PR **squash merge** qilinadi —
  egasidan qayta so'rash shart emas.
- Foydalanuvchiga javoblar o'zbek tilida.
