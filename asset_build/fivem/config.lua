ShredderConfig = {}

-- Add as many recipes as you need. Item names must exist in ox_inventory.
-- GTA/FiveM prop names work directly; custom models must also be streamed.
ShredderConfig.Recipes = {
    --[[
    {
        id = 'car_door',
        label = 'Scrap Car Door',
        inputItem = 'car_door',
        inputModel = 'prop_car_door_01',
        outputItem = 'metalscrap',
        outputModel = 'industrial_scrap_shredder_v25_chunk',
        outputMin = 2,
        outputMax = 4
    },
    {
        id = 'damaged_car_part',
        label = 'Damaged Car Part',
        inputItem = 'damaged_car_part',
        inputModel = 'prop_rub_carpart_05',
        outputItem = 'steel',
        outputModel = 'industrial_scrap_shredder_v25_chunk',
        outputMin = 1,
        outputMax = 3
    }
    ]]
}

ShredderConfig.PlacementRemoveDistance = 20.0
ShredderConfig.InteractionDistance = 3.0
ShredderConfig.PendingProcessTimeoutSeconds = 90
ShredderConfig.OutputCollectTimeoutSeconds = 120
ShredderConfig.SoundEnabled = true
ShredderConfig.SoundMaxDistance = 45.0
ShredderConfig.SoundVolume = 0.42
ShredderConfig.MaxActiveSounds = 3
