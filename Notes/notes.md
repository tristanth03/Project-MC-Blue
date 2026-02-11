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
* Biome

## PlayerViewingEnvironment
* See 