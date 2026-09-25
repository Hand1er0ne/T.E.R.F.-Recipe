# TERF Recipes

Mod Fabric (Minecraft 26.1.2) qui affiche dans **JEI** les recettes des machines du datapack
[TERF](https://www.planetminecraft.com/data-pack/troty-energy-research-facility-infinity-update/)
et ses items customs.

Aucune recette n'est codée en dur : le mod **rejoue `data/terf/function/startup.mcfunction`**
(uniquement les commandes `data modify/remove storage`), reconstruit le storage
`terf:constants` en mémoire, puis le lit exactement comme les machines du datapack.
Si le datapack ajoute une recette ou même une nouvelle machine, elle apparaît toute seule.

## Où le mod cherche le datapack

Dans cet ordre (la première source trouvée gagne) :

1. **Solo** : les datapacks activés dans le monde (lus via le serveur intégré).
2. **`config/terf-recipes/`** : un `startup.mcfunction`, ou le zip / dossier du datapack.
3. **Resource pack du serveur** : si le serveur envoie le zip TERF complet (assets + data)
   comme resource pack, le mod le lit dans `.minecraft/downloads`.
4. **Copie intégrée au mod** : copiée depuis `TERF_datapack/` à chaque build.

> Un serveur n'envoie jamais ses fichiers `.mcfunction` au client : en multijoueur ce sont
> les sources 2 à 4 qui servent. Pour être à jour, dépose le datapack du serveur dans
> `config/terf-recipes/` puis fais `/terfrecipes reload`.

## Masquer des recettes

`config/terf-recipes/hidden.txt` (créé au premier lancement, avec des exemples), puis
`/terfrecipes reload` :

```
machine:dem                      # tout l'onglet d'une machine (et sa structure)
output:terf:gravity_gun          # les recettes qui produisent cet item
input:minecraft:netherite_ingot  # les recettes qui utilisent cet ingrédient
recipe:fabricator/*gold*         # par identifiant (visible avec F3+H sur le résultat)
item:terf:command_block_staff    # retire un item custom de la liste JEI
```

## Commandes (côté client)

- `/terfrecipes` : nombre de recettes / items et source utilisée
- `/terfrecipes reload` : relit le datapack et met à jour JEI sans relancer le jeu
  (une machine *nouvelle* demande de rejoindre le monde pour avoir son onglet)

## Ce qui est affiché

- Un onglet JEI par machine : Fabricator (grille 3x3), Assembler, Crusher, Rolling Mill,
  Extrusion / Shearing / Electric Press, EBF, Hadron Collider, OpenCore, Electrolyzer,
  Wet Mill, Large Fluid Solidifier, Fission Fuel Loader, DEM...
- Les items customs (plaques, vis, rotors, outils...) construits avec les mêmes composants
  que `terf:require/custom_item_summon` et ajoutés à la liste JEI (R / U fonctionnent).
- Les fluides sous forme de seau nommé (quantité dans l'infobulle).
- Les sorties aléatoires (ex. `random_ore`) avec leur probabilité.
- Les paramètres des recettes (temps, quantités, longueur d'anneau...) et les étapes OpenCore.

Les textures des items customs viennent du resource pack TERF : il doit être chargé
(dans `resourcepacks/` ou envoyé par le serveur).

## Structures des machines (onglet « TERF Multiblocks »)

Le datapack n'a pas de fichiers de structure : la table `mb_setup_functions` (dans
`startup.mcfunction`) donne le bloc où poser le Multiblock Core et la fonction de setup de
chaque machine, et la forme n'existe que sous forme de conditions `if block ^x ^y ^z <bloc>`
dans les fonctions setup / tick / checks. Le mod suit ces fonctions et reconstruit la structure.

- Première page : la liste des blocs avec leur quantité (U sur un bloc = machines qui l'utilisent).
- Machines simples : une page par couche, vue de dessus (tu es en bas de la grille, face à la
  machine ; le cadre rouge = bloc où poser le Core). Infobulles : états du bloc, entrée d'énergie,
  port de fluide.
- Grosses machines (STFR, Warp Core, OpenCore, Hadron Collider...) : liste seulement.
  Réglable par machine avec `"structure": "layers" | "list" | "hidden"` dans `machines.json`.

C'est une lecture automatique du code du datapack : pour les machines vérifiées par des boucles
ou des fonctions dynamiques (STFR par exemple), la liste peut être incomplète.

## Personnaliser l'affichage

`src/main/resources/terf-recipes/machines.json` décrit seulement la présentation
(nom, icône, signification des paramètres `t`, `a`, `x`...). Copie-le dans
`config/terf-recipes/machines.json` pour le modifier sans recompiler. Une machine absente
du fichier est quand même affichée avec des valeurs par défaut.

## Code

- `com.terfrecipes.data` : parser SNBT, chemins NBT, émulateur de storage, extraction des
  recettes. Aucune dépendance à Minecraft (testable seul).
- `com.terfrecipes.client` : recherche des sources, construction des ItemStacks, commande.
- `com.terfrecipes.client.jei` : plugin et catégories JEI.
