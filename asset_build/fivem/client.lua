local MODEL_NAME = 'vrp_scrap_shredder'
local spawnedShredder

local function notify(message)
    BeginTextCommandThefeedPost('STRING')
    AddTextComponentSubstringPlayerName(message)
    EndTextCommandThefeedPostTicker(false, false)
end

local function deleteTestShredder()
    if spawnedShredder and DoesEntityExist(spawnedShredder) then
        SetEntityAsMissionEntity(spawnedShredder, true, true)
        DeleteEntity(spawnedShredder)
    end

    spawnedShredder = nil
end

RegisterCommand('spawnshredder', function(_, args)
    if GetConvarInt('vrp_shredder_test_commands', 1) ~= 1 then
        notify('Shredder test commands are disabled.')
        return
    end

    local model = joaat(MODEL_NAME)
    if not IsModelInCdimage(model) or not IsModelValid(model) then
        notify(('Could not load model ~r~%s~s~.'):format(MODEL_NAME))
        return
    end

    RequestModel(model)
    local timeout = GetGameTimer() + 10000
    while not HasModelLoaded(model) and GetGameTimer() < timeout do
        Wait(0)
    end

    if not HasModelLoaded(model) then
        notify('Timed out loading the shredder model.')
        return
    end

    deleteTestShredder()

    local ped = PlayerPedId()
    local position = GetOffsetFromEntityInWorldCoords(ped, 0.0, 6.0, 0.0)
    local heading = tonumber(args[1]) or GetEntityHeading(ped)

    spawnedShredder = CreateObjectNoOffset(
        model,
        position.x,
        position.y,
        position.z,
        true,
        true,
        false
    )

    if not spawnedShredder or spawnedShredder == 0 then
        SetModelAsNoLongerNeeded(model)
        notify('The shredder could not be created.')
        return
    end

    SetEntityAsMissionEntity(spawnedShredder, true, true)
    SetEntityHeading(spawnedShredder, heading + 0.0)
    PlaceObjectOnGroundProperly(spawnedShredder)
    FreezeEntityPosition(spawnedShredder, true)
    SetModelAsNoLongerNeeded(model)

    local coords = GetEntityCoords(spawnedShredder)
    print(('[vrp-scrap-shredder] Spawned at vector4(%.4f, %.4f, %.4f, %.4f)')
        :format(coords.x, coords.y, coords.z, GetEntityHeading(spawnedShredder)))
    notify('Test shredder spawned. Use ~y~/deleteshredder~s~ to remove it.')
end, false)

RegisterCommand('deleteshredder', function()
    if GetConvarInt('vrp_shredder_test_commands', 1) ~= 1 then
        notify('Shredder test commands are disabled.')
        return
    end

    if not spawnedShredder or not DoesEntityExist(spawnedShredder) then
        notify('There is no test shredder to delete.')
        return
    end

    deleteTestShredder()
    notify('Test shredder removed.')
end, false)

AddEventHandler('onResourceStop', function(resourceName)
    if resourceName == GetCurrentResourceName() then
        deleteTestShredder()
    end
end)
