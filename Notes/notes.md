# Notes

## ID (string)
* Format: number_InGameTick__realTimeStamp
    * With realTimeStamp : yyyyMMdd_HHmm_SSS
    * e.g. 0_1233__20260210_519 


## PlayerCords
* X position ($\in \mathbb{R}$; stored as a double)
    * e.g 90.12
* Y position ($\in \mathbb{R}$; stored as a double)
    * e.g. 60
* Z position ($\in \mathbb{R}$; stored as a double)
    * e.g. -100.23


## PlayerViewingCords
* X vewing direction ($\in [-1,1]$; stored as a double)
    * e.g -0.22
* Y vewing direction ($\in [-1,1]$; stored as a double)
    * e.g. 0.991
* Z vewing direction ($\in [-1,1]$; stored as a double)
    * e.g. -0.73314

## PlayerStats
* Health ($\in [0,20]\cap \mathbb{N}_0$; stored as an int)
* FoodLevel ($\in [0,20]\cap \mathbb{N}_0$; stored as an int)

## PlayerEnvironment
* Biome (string)
    * e.g. frozen_lake

* 

## PlayerViewingEnvironment

#### Environment
* OutsideObservableFlag ($\in \{0,1\}$; stored as boolean)
    * e.g. 1 (if inside cave with openining that allows light to be obserable, or just outside in general)
    * Notes: ensures fairness for other features, i.e. does not leak unobservable data.

* NightFlag ($\in \{0,1\}$; stored as boolean)
    * e.g. 1 (if there is night and it is observable, but for instance is "none" if one is inside a deep cave with no openening)
    * ! *Possibly update s.t. it works in the nether, end etc...* 


#### Blocks
* BlockHighlightedFlag ($\in \{0,1\}$; stored as boolean)
    * Whether a block is highlighted or not

* HighlightedBlock (string)
    * e.g. coblestone

#### Mobs
* ViewingMobFlag ($\in \{0,1\}$; stored as boolean)
    * Mob or even mobs that are clearly visable withing viewing cone and ray
    * Notes: Roughly works as the graphics do.
    * ! *Might be a bit to powerful (is set s.t. one is playing fullscreen with all visual at the maximum, including brightness, fps, chunks etc...)*

* ClosestViewingMobType (string)
    * e.g. cow 

* AllViewingMobs (semi-comma seperated string, with naming and count)
    * e.g. cow : 1; chicken : 2



## Inventory
#### Hotbar
* Item1Hotbar (string)
    * e.g. shovel
* Item2Hotbar (string)
    * e.g. apple : 4
* Item3Hotbar (string)
* Item4Hotbar (string)
* Item5Hotbar (string)
* Item6Hotbar (string)
* Item7Hotbar (string)
* Item8Hotbar (string)
* Item9Hotbar (string)





