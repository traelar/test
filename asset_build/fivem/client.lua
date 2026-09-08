local MODEL_NAMES = {
    base = 'industrial_scrap_shredder_v24',
    rotorA = 'industrial_scrap_shredder_v24_rotor_a',
    rotorB = 'industrial_scrap_shredder_v24_rotor_b',
    beltIn = 'industrial_scrap_shredder_v24_belt_in',
    beltOut = 'industrial_scrap_shredder_v24_belt_out',
    chunk = 'industrial_scrap_shredder_v24_chunk'
}

local ROTOR_A_OFFSET = vector3(-0.39, 0.0, 2.44)
local ROTOR_B_OFFSET = vector3(0.39, 0.0, 2.44)
local INPUT_ANGLE = math.rad(-38.5)
local OUTPUT_ANGLE = math.rad(11.0)
local BELT_PITCH = 0.265
local INPUT_CENTER = vector3(-3.95, 0.0, 2.60)
local INPUT_HALF_LENGTH = 3.30
local OUTPUT_CENTER = vector3(2.55, 0.0, 0.92)
local OUTPUT_HALF_LENGTH = 1.375
local INTERACTION_RADIUS = 70.0
local PROP_SCAN_INTERVAL = 220
local OUTPUT_PIECE_MODELS = { MODEL_NAMES.chunk }
local assemblies = {}
local spawnedTestBase
local observedBases = {}
local targetZones = {}
local outputPieces = {}
local placementBases = {}
local scrapEntities = {}
local playerShredCooldown = 0
local requestPlaceScrap

local MODEL_HASHES = {}
for _, modelName in pairs(MODEL_NAMES) do
    MODEL_HASHES[joaat(modelName)] = true
end
local SCRAP_CHUNK_HASHES = {
    [joaat(MODEL_NAMES.chunk)] = true,
    -- Lets the new cleanup remove debris left behind by the v2.3 test build.
    [joaat('industrial_scrap_shredder_v23_chunk')] = true
}
MODEL_HASHES[joaat('industrial_scrap_shredder_v23_chunk')] = true

local function notify(message)
    BeginTextCommandThefeedPost('STRING')
    AddTextComponentSubstringPlayerName(message)
    EndTextCommandThefeedPostTicker(false, false)
end

local function requestModels()
    local timeout = GetGameTimer() + 10000

    for _, modelName in pairs(MODEL_NAMES) do
        local model = joaat(modelName)
        if not IsModelInCdimage(model) or not IsModelValid(model) then
            return false, modelName
        end
        RequestModel(model)
    end

    for _, modelName in pairs(MODEL_NAMES) do
        local model = joaat(modelName)
        while not HasModelLoaded(model) and GetGameTimer() < timeout do
            Wait(0)
        end
        if not HasModelLoaded(model) then
            return false, modelName
        end
    end

    return true
end

local function releaseModels()
    for _, modelName in pairs(MODEL_NAMES) do
        SetModelAsNoLongerNeeded(joaat(modelName))
    end
end

local function safeDelete(entity)
    if entity and DoesEntityExist(entity) then
        if NetworkGetEntityIsNetworked(entity) and not NetworkHasControlOfEntity(entity) then
            NetworkRequestControlOfEntity(entity)
            local timeout = GetGameTimer() + 350
            while DoesEntityExist(entity) and not NetworkHasControlOfEntity(entity) and
                GetGameTimer() < timeout do
                Wait(0)
                NetworkRequestControlOfEntity(entity)
            end
        end
        SetEntityAsMissionEntity(entity, true, true)
        DeleteObject(entity)
        DeleteEntity(entity)
    end
end

local function isShredderEnabled(base)
    return DoesEntityExist(base) and Entity(base).state.shredderEnabled == true
end

local function toggleShredder(base)
    if not base or not DoesEntityExist(base) then
        notify('The shredder is no longer available.')
        return
    end

    local assembly = assemblies[base]
    if assembly and assembly.placementId then
        TriggerServerEvent('vrp-scrap-shredder:server:togglePlacement', assembly.placementId)
    elseif NetworkGetEntityIsNetworked(base) then
        TriggerServerEvent('vrp-scrap-shredder:server:toggle', NetworkGetNetworkIdFromEntity(base))
    else
        local enabled = not isShredderEnabled(base)
        Entity(base).state:set('shredderEnabled', enabled, false)
    end
end

RegisterNetEvent('vrp-scrap-shredder:client:notify', function(message)
    notify(message)
end)

RegisterNetEvent('vrp-scrap-shredder:client:targetToggle', function(data)
    toggleShredder(data and (data.base or data.entity))
end)

RegisterNetEvent('vrp-scrap-shredder:client:targetPlaceRecipe', function(data)
    if requestPlaceScrap then
        requestPlaceScrap(data and (data.base or data.entity), data and data.recipeId)
    end
end)

local function removeTarget(base)
    local zone = targetZones[base]
    if not zone then
        return
    end

    if zone.provider == 'ox' and GetResourceState('ox_target') == 'started' then
        for _, zoneId in ipairs(zone.ids) do
            exports.ox_target:removeZone(zoneId)
        end
    elseif zone.provider == 'qb' and GetResourceState('qb-target') == 'started' then
        for _, zoneId in ipairs(zone.ids) do
            exports['qb-target']:RemoveZone(zoneId)
        end
    end
    targetZones[base] = nil
end

local function registerTarget(base)
    if targetZones[base] or not DoesEntityExist(base) then
        return
    end

    -- The box is intentionally wider/deeper than the physical cabinet and sits
    -- slightly in front of it so normal standing angles acquire the target.
    local center = GetOffsetFromEntityInWorldCoords(base, 1.82, -1.74, 1.57)
    local heading = GetEntityHeading(base)
    local zoneName = ('industrial_shredder_panel_%s'):format(base)
    local feedZoneName = ('industrial_shredder_feed_%s'):format(base)
    local recipes = ShredderConfig and ShredderConfig.Recipes or {}

    if GetResourceState('ox_target') == 'started' then
        local zoneIds = {}
        zoneIds[#zoneIds + 1] = exports.ox_target:addBoxZone({
            coords = center,
            size = vector3(0.92, 1.05, 1.12),
            rotation = heading,
            debug = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
            options = {
                {
                    name = zoneName,
                    icon = 'fa-solid fa-power-off',
                    label = 'Toggle industrial shredder',
                    distance = 3.0,
                    canInteract = function()
                        return DoesEntityExist(base)
                    end,
                    onSelect = function()
                        toggleShredder(base)
                    end
                }
            }
        })
        local feedOptions = {}
        for _, recipe in ipairs(recipes) do
            if recipe.enabled ~= false then
                local recipeId = recipe.id
                feedOptions[#feedOptions + 1] = {
                    name = ('%s_%s'):format(feedZoneName, recipeId),
                    icon = 'fa-solid fa-gears',
                    label = ('Place %s on conveyor'):format(recipe.label or recipeId),
                    distance = ShredderConfig.InteractionDistance or 3.0,
                    canInteract = function()
                        return DoesEntityExist(base) and isShredderEnabled(base)
                    end,
                    onSelect = function()
                        requestPlaceScrap(base, recipeId)
                    end
                }
            end
        end
        if #feedOptions > 0 then
            zoneIds[#zoneIds + 1] = exports.ox_target:addBoxZone({
                coords = GetOffsetFromEntityInWorldCoords(base, -6.18, 0.0, 0.82),
                size = vector3(1.45, 2.35, 1.20),
                rotation = heading,
                debug = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
                options = feedOptions
            })
        end
        targetZones[base] = { provider = 'ox', ids = zoneIds }
    elseif GetResourceState('qb-target') == 'started' then
        exports['qb-target']:AddBoxZone(zoneName, center, 0.92, 1.05, {
            name = zoneName,
            heading = heading,
            debugPoly = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
            minZ = center.z - 0.56,
            maxZ = center.z + 0.56
        }, {
            options = {
                {
                    type = 'client',
                    event = 'vrp-scrap-shredder:client:targetToggle',
                    icon = 'fas fa-power-off',
                    label = 'Toggle industrial shredder',
                    base = base
                }
            },
            distance = 3.0
        })
        local zoneIds = { zoneName }
        local feedOptions = {}
        for _, recipe in ipairs(recipes) do
            if recipe.enabled ~= false then
                feedOptions[#feedOptions + 1] = {
                    type = 'client',
                    event = 'vrp-scrap-shredder:client:targetPlaceRecipe',
                    icon = 'fas fa-gears',
                    label = ('Place %s on conveyor'):format(recipe.label or recipe.id),
                    base = base,
                    recipeId = recipe.id,
                    canInteract = function()
                        return DoesEntityExist(base) and isShredderEnabled(base)
                    end
                }
            end
        end
        if #feedOptions > 0 then
            local feedCenter = GetOffsetFromEntityInWorldCoords(base, -6.18, 0.0, 0.82)
            exports['qb-target']:AddBoxZone(feedZoneName, feedCenter, 1.45, 2.35, {
                name = feedZoneName,
                heading = heading,
                debugPoly = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
                minZ = feedCenter.z - 0.60,
                maxZ = feedCenter.z + 0.60
            }, {
                options = feedOptions,
                distance = ShredderConfig.InteractionDistance or 3.0
            })
            zoneIds[#zoneIds + 1] = feedZoneName
        end
        targetZones[base] = { provider = 'qb', ids = zoneIds }
    end
end

local function localDirectionToWorld(base, x, y, z)
    local origin = GetEntityCoords(base)
    local endpoint = GetOffsetFromEntityInWorldCoords(base, x, y, z)
    local direction = endpoint - origin
    return direction / #direction
end

local function inputBeltHeight(localX)
    local along = (localX - INPUT_CENTER.x) / math.cos(INPUT_ANGLE)
    return INPUT_CENTER.z - math.sin(INPUT_ANGLE) * along
end

local function outputBeltHeight(localX)
    local along = (localX - OUTPUT_CENTER.x) / math.cos(OUTPUT_ANGLE)
    return OUTPUT_CENTER.z - math.sin(OUTPUT_ANGLE) * along
end

local function entityLocalCoords(entity, base)
    local coords = GetEntityCoords(entity)
    return GetOffsetFromEntityGivenWorldCoords(base, coords.x, coords.y, coords.z)
end

local function isOnInputBelt(entity, base)
    local localCoords = entityLocalCoords(entity, base)
    local beltZ = inputBeltHeight(localCoords.x)
    return localCoords.x >= -6.48 and localCoords.x <= -1.48 and
        math.abs(localCoords.y) <= 1.02 and
        localCoords.z >= beltZ - 0.22 and localCoords.z <= beltZ + 1.65
end

local function isOnOutputBelt(entity, base)
    local localCoords = entityLocalCoords(entity, base)
    local beltZ = outputBeltHeight(localCoords.x)
    return localCoords.x >= OUTPUT_CENTER.x - OUTPUT_HALF_LENGTH - 0.10 and
        localCoords.x <= OUTPUT_CENTER.x + OUTPUT_HALF_LENGTH + 0.12 and
        math.abs(localCoords.y) <= 1.00 and
        localCoords.z >= beltZ - 0.30 and localCoords.z <= beltZ + 1.20
end

local function isInsideCutterThroat(entity, base)
    local localCoords = entityLocalCoords(entity, base)
    return localCoords.x >= -1.20 and localCoords.x <= 1.20 and
        math.abs(localCoords.y) <= 1.08 and
        localCoords.z >= 2.28 and localCoords.z <= 4.18
end

local function carryEntity(entity, base, angle, speed)
    local direction = localDirectionToWorld(base, math.cos(angle), 0.0, -math.sin(angle))
    SetEntityVelocity(entity, direction.x * speed, direction.y * speed, direction.z * speed)
end

local function requestPieceModel(modelName)
    local model = joaat(modelName)
    if not IsModelInCdimage(model) or not IsModelValid(model) then
        return nil
    end
    RequestModel(model)
    local timeout = GetGameTimer() + 1500
    while not HasModelLoaded(model) and GetGameTimer() < timeout do
        Wait(0)
    end
    return HasModelLoaded(model) and model or nil
end

local function resolveMachine(machineType, machineId)
    if machineType == 'placement' then
        local base = placementBases[tonumber(machineId)]
        return base and DoesEntityExist(base) and base or nil
    elseif machineType == 'network' then
        local base = NetworkGetEntityFromNetworkId(tonumber(machineId) or 0)
        return base ~= 0 and DoesEntityExist(base) and base or nil
    end
    return nil
end

requestPlaceScrap = function(base, recipeId)
    if not base or not DoesEntityExist(base) or not isShredderEnabled(base) then
        notify('Switch the shredder on first.')
        return
    end

    local assembly = assemblies[base]
    if assembly and assembly.placementId then
        TriggerServerEvent('vrp-scrap-shredder:server:placeRecipe',
            recipeId, 'placement', assembly.placementId)
    elseif NetworkGetEntityIsNetworked(base) then
        TriggerServerEvent('vrp-scrap-shredder:server:placeRecipe',
            recipeId, 'network', NetworkGetNetworkIdFromEntity(base))
    else
        notify('This shredder is not registered for inventory processing.')
    end
end

RegisterNetEvent('vrp-scrap-shredder:client:spawnRecipeInput',
    function(token, recipe, machineType, machineId)
        local base = resolveMachine(machineType, machineId)
        local model = recipe and requestPieceModel(recipe.inputModel)
        if not base or not model then
            TriggerServerEvent('vrp-scrap-shredder:server:cancelProcess', token)
            notify('The configured input prop could not be spawned.')
            return
        end

        local spawn = GetOffsetFromEntityInWorldCoords(base, -6.15, 0.0, 1.02)
        local object = CreateObjectNoOffset(model, spawn.x, spawn.y, spawn.z, true, true, false)
        SetModelAsNoLongerNeeded(model)
        if not object or object == 0 then
            TriggerServerEvent('vrp-scrap-shredder:server:cancelProcess', token)
            notify('The configured input prop could not be spawned.')
            return
        end

        SetEntityAsMissionEntity(object, true, true)
        SetEntityDynamic(object, true)
        ActivatePhysics(object)
        Entity(object).state:set('shredderInputToken', token, true)
        scrapEntities[object] = { token = token, base = base }
        carryEntity(object, base, INPUT_ANGLE, 1.32)
    end)

RegisterNetEvent('vrp-scrap-shredder:client:spawnProcessedOutput',
    function(token, recipe, machineType, machineId, sourceModel)
        local base = resolveMachine(machineType, machineId)
        local modelName = recipe and recipe.outputModel or MODEL_NAMES.chunk
        local model = requestPieceModel(modelName)
        if not base or not model then
            TriggerServerEvent('vrp-scrap-shredder:server:cancelOutput', token)
            return
        end

        local spawn = GetOffsetFromEntityInWorldCoords(base, 1.50, 0.0, 1.38)
        local piece = CreateObjectNoOffset(model, spawn.x, spawn.y, spawn.z, true, true, false)
        SetModelAsNoLongerNeeded(model)
        if not piece or piece == 0 then
            TriggerServerEvent('vrp-scrap-shredder:server:cancelOutput', token)
            return
        end

        SetEntityAsMissionEntity(piece, true, true)
        SetEntityDynamic(piece, true)
        ActivatePhysics(piece)
        SetEntityRotation(piece, math.random(0, 359) + 0.0,
            math.random(0, 359) + 0.0, math.random(0, 359) + 0.0, 2, true)
        Entity(piece).state:set('shredderOutputToken', token, true)
        outputPieces[piece] = {
            expiresAt = GetGameTimer() +
                ((ShredderConfig.OutputCollectTimeoutSeconds or 120) * 1000),
            base = base,
            collectible = true,
            token = token
        }
        TriggerServerEvent('vrp-scrap-shredder:server:registerOutput',
            token, NetworkGetNetworkIdFromEntity(piece))
        carryEntity(piece, base, OUTPUT_ANGLE, 2.15)
        TriggerEvent('vrp-scrap-shredder:shredded', base, sourceModel, 1, recipe.id)
    end)

RegisterNetEvent('vrp-scrap-shredder:client:deleteProcessedOutput', function(netId)
    local entity = NetworkGetEntityFromNetworkId(tonumber(netId) or 0)
    if entity ~= 0 then
        outputPieces[entity] = nil
        safeDelete(entity)
    end
end)

RegisterNetEvent('vrp-scrap-shredder:client:targetCollectOutput', function(data)
    local entity = data and (data.entity or data.target)
    if not entity or not DoesEntityExist(entity) then
        return
    end
    local token = Entity(entity).state.shredderOutputToken
    if token then
        TriggerServerEvent('vrp-scrap-shredder:server:collectOutput',
            token, NetworkGetNetworkIdFromEntity(entity))
    end
end)

local function countOutputPieces()
    local count = 0
    for entity in pairs(outputPieces) do
        if DoesEntityExist(entity) then
            count = count + 1
        end
    end
    return count
end

local function spawnOutputPieces(base, sourceModel)
    local requestedCount = math.max(1, math.min(8, GetConvarInt('vrp_shredder_piece_count', 4)))
    local available = math.max(0, 48 - countOutputPieces())
    local pieceCount = math.min(requestedCount, available)
    local lifetime = math.max(5000, GetConvarInt('vrp_shredder_piece_lifetime_ms', 45000))

    for index = 1, pieceCount do
        local modelName = OUTPUT_PIECE_MODELS[((index - 1) % #OUTPUT_PIECE_MODELS) + 1]
        local model = requestPieceModel(modelName)
        if model then
            local y = ((index % 3) - 1) * 0.30
            local spawn = GetOffsetFromEntityInWorldCoords(base, 1.48, y, 1.34 + index * 0.035)
            local networked = NetworkGetEntityIsNetworked(base)
            local piece = CreateObjectNoOffset(model, spawn.x, spawn.y, spawn.z,
                networked, networked, false)
            if piece and piece ~= 0 then
                SetEntityAsMissionEntity(piece, true, true)
                SetEntityDynamic(piece, true)
                ActivatePhysics(piece)
                SetEntityRotation(piece, math.random(0, 359) + 0.0,
                    math.random(0, 359) + 0.0, math.random(0, 359) + 0.0, 2, true)
                outputPieces[piece] = {
                    expiresAt = GetGameTimer() + lifetime,
                    base = base
                }
                carryEntity(piece, base, OUTPUT_ANGLE, 2.15 + index * 0.08)
            end
            SetModelAsNoLongerNeeded(model)
        end
    end

    TriggerEvent('vrp-scrap-shredder:shredded', base, sourceModel, pieceCount)
end

local function shredObject(object, base)
    if not DoesEntityExist(object) or MODEL_HASHES[GetEntityModel(object)] then
        return
    end
    if NetworkGetEntityIsNetworked(object) and not NetworkHasControlOfEntity(object) then
        NetworkRequestControlOfEntity(object)
        return
    end

    local sourceModel = GetEntityModel(object)
    local recipeProcess = scrapEntities[object]
    safeDelete(object)
    scrapEntities[object] = nil
    if recipeProcess then
        TriggerServerEvent('vrp-scrap-shredder:server:processComplete', recipeProcess.token)
    else
        spawnOutputPieces(base, sourceModel)
    end
end

local function attachComponent(entity, base, offsetX, offsetY, offsetZ, rotationY)
    AttachEntityToEntity(
        entity, base, 0,
        offsetX, offsetY, offsetZ,
        0.0, rotationY, 0.0,
        false, false, false, false, 2, true
    )
end

local function createComponent(modelName, base, networked)
    local coords = GetEntityCoords(base)
    local entity = CreateObjectNoOffset(
        joaat(modelName), coords.x, coords.y, coords.z,
        networked, networked, false
    )

    if not entity or entity == 0 then
        return nil
    end

    SetEntityAsMissionEntity(entity, true, true)
    SetEntityCollision(entity, false, false)
    return entity
end

local function updateAssembly(assembly, deltaTime)
    if isShredderEnabled(assembly.base) then
        assembly.rotorAngle = (assembly.rotorAngle + (deltaTime * 115.0)) % 360.0
        assembly.inputPhase = (assembly.inputPhase + (deltaTime * 0.72)) % BELT_PITCH
        assembly.outputPhase = (assembly.outputPhase + (deltaTime * 0.55)) % BELT_PITCH
    end

    attachComponent(assembly.rotorA, assembly.base,
        ROTOR_A_OFFSET.x, ROTOR_A_OFFSET.y, ROTOR_A_OFFSET.z, assembly.rotorAngle)
    attachComponent(assembly.rotorB, assembly.base,
        ROTOR_B_OFFSET.x, ROTOR_B_OFFSET.y, ROTOR_B_OFFSET.z, -assembly.rotorAngle)

    local inputX = math.cos(INPUT_ANGLE) * assembly.inputPhase
    local inputZ = -math.sin(INPUT_ANGLE) * assembly.inputPhase
    attachComponent(assembly.beltIn, assembly.base, inputX, 0.0, inputZ, 0.0)

    local outputX = math.cos(OUTPUT_ANGLE) * assembly.outputPhase
    local outputZ = -math.sin(OUTPUT_ANGLE) * assembly.outputPhase
    attachComponent(assembly.beltOut, assembly.base, outputX, 0.0, outputZ, 0.0)
end

local function cleanupOutputPieces(base)
    local baseCoords = DoesEntityExist(base) and GetEntityCoords(base) or nil

    for piece, data in pairs(outputPieces) do
        local owner = type(data) == 'table' and data.base or nil
        if owner == base then
            if data.collectible and data.token then
                TriggerServerEvent('vrp-scrap-shredder:server:cancelOutput', data.token)
            end
            safeDelete(piece)
            outputPieces[piece] = nil
        end
    end

    -- Also catches networked/untracked chunks and debris left by v2.3. The
    -- model filter prevents unrelated world objects from ever being touched.
    if baseCoords then
        for _, object in ipairs(GetGamePool('CObject')) do
            if DoesEntityExist(object) and SCRAP_CHUNK_HASHES[GetEntityModel(object)] and
                #(GetEntityCoords(object) - baseCoords) <= 16.0 then
                safeDelete(object)
                outputPieces[object] = nil
            end
        end
    end
end

local function cleanupInputScrap(base)
    for object, data in pairs(scrapEntities) do
        if data.base == base then
            TriggerServerEvent('vrp-scrap-shredder:server:cancelProcess', data.token)
            safeDelete(object)
            scrapEntities[object] = nil
        end
    end
end

local function deleteAnimatedShredder(base)
    local assembly = assemblies[base]
    removeTarget(base)
    if not (assembly and assembly.preview) then
        cleanupOutputPieces(base)
        cleanupInputScrap(base)
    end
    if not assembly then
        safeDelete(base)
        observedBases[base] = nil
        if spawnedTestBase == base then
            spawnedTestBase = nil
        end
        return
    end

    local placementId = assembly.placementId

    safeDelete(assembly.rotorA)
    safeDelete(assembly.rotorB)
    safeDelete(assembly.beltIn)
    safeDelete(assembly.beltOut)
    safeDelete(assembly.base)
    assemblies[base] = nil
    observedBases[base] = nil
    if placementId then
        placementBases[placementId] = nil
    end

    if spawnedTestBase == base then
        spawnedTestBase = nil
    end
end

local function createAnimatedShredder(coords, heading, networked, preview)
    local loaded, failedModel = requestModels()
    if not loaded then
        releaseModels()
        return nil, ('Could not load model %s'):format(failedModel or 'unknown')
    end

    networked = networked == true
    local base = CreateObjectNoOffset(
        joaat(MODEL_NAMES.base), coords.x, coords.y, coords.z,
        networked, networked, false
    )

    if not base or base == 0 then
        releaseModels()
        return nil, 'Could not create the shredder base'
    end

    SetEntityAsMissionEntity(base, true, true)
    SetEntityHeading(base, heading + 0.0)
    PlaceObjectOnGroundProperly(base)
    FreezeEntityPosition(base, true)

    local assembly = {
        base = base,
        rotorA = createComponent(MODEL_NAMES.rotorA, base, networked),
        rotorB = createComponent(MODEL_NAMES.rotorB, base, networked),
        beltIn = createComponent(MODEL_NAMES.beltIn, base, networked),
        beltOut = createComponent(MODEL_NAMES.beltOut, base, networked),
        rotorAngle = 0.0,
        inputPhase = 0.0,
        outputPhase = 0.0,
        preview = preview == true
    }

    if not assembly.rotorA or not assembly.rotorB or not assembly.beltIn or not assembly.beltOut then
        safeDelete(assembly.rotorA)
        safeDelete(assembly.rotorB)
        safeDelete(assembly.beltIn)
        safeDelete(assembly.beltOut)
        safeDelete(base)
        releaseModels()
        return nil, 'Could not create one or more animated components'
    end

    assemblies[base] = assembly
    Entity(base).state:set('shredderEnabled', false, networked)
    updateAssembly(assembly, 0.0)
    if preview then
        SetEntityCollision(base, false, false)
        SetEntityAlpha(base, 155, false)
        for _, entity in ipairs({ assembly.rotorA, assembly.rotorB,
            assembly.beltIn, assembly.beltOut }) do
            SetEntityAlpha(entity, 155, false)
        end
    else
        registerTarget(base)
    end
    releaseModels()
    return base
end

local function spawnPlacement(placement)
    if type(placement) ~= 'table' or type(placement.id) ~= 'number' then
        return
    end

    local existing = placementBases[placement.id]
    if existing and DoesEntityExist(existing) then
        Entity(existing).state:set('shredderEnabled', placement.enabled == true, false)
        return
    end

    local base, errorMessage = createAnimatedShredder(
        vector3(placement.x + 0.0, placement.y + 0.0, placement.z + 0.0),
        placement.heading + 0.0,
        false
    )
    if not base then
        print(('[industrial-scrap-shredder] Could not spawn saved placement %s: %s')
            :format(placement.id, errorMessage or 'unknown error'))
        return
    end

    assemblies[base].placementId = placement.id
    placementBases[placement.id] = base
    Entity(base).state:set('shredderEnabled', placement.enabled == true, false)
end

local function removePlacement(placementId)
    local base = placementBases[tonumber(placementId)]
    if base then
        deleteAnimatedShredder(base)
    end
    placementBases[tonumber(placementId)] = nil
end

RegisterNetEvent('vrp-scrap-shredder:client:syncPlacements', function(placements)
    local seen = {}
    for _, placement in ipairs(type(placements) == 'table' and placements or {}) do
        seen[placement.id] = true
        spawnPlacement(placement)
    end

    local stale = {}
    for placementId in pairs(placementBases) do
        if not seen[placementId] then
            stale[#stale + 1] = placementId
        end
    end
    for i = 1, #stale do
        removePlacement(stale[i])
    end
end)

RegisterNetEvent('vrp-scrap-shredder:client:addPlacement', function(placement)
    spawnPlacement(placement)
end)

RegisterNetEvent('vrp-scrap-shredder:client:removePlacement', function(placementId)
    removePlacement(placementId)
end)

RegisterNetEvent('vrp-scrap-shredder:client:setPlacementState', function(placementId, enabled)
    local base = placementBases[tonumber(placementId)]
    if base and DoesEntityExist(base) then
        Entity(base).state:set('shredderEnabled', enabled == true, false)
    end
end)

exports('CreateAnimatedShredder', createAnimatedShredder)
exports('DeleteAnimatedShredder', deleteAnimatedShredder)

CreateThread(function()
    Wait(1500)
    TriggerServerEvent('vrp-scrap-shredder:server:requestPlacements')
end)

local processedTargetRegistration
local function removeProcessedTargets()
    if not processedTargetRegistration then return end
    if processedTargetRegistration.provider == 'ox' and
        GetResourceState('ox_target') == 'started' then
        exports.ox_target:removeModel(processedTargetRegistration.models,
            { 'industrial_shredder_collect_output' })
    elseif processedTargetRegistration.provider == 'qb' and
        GetResourceState('qb-target') == 'started' then
        exports['qb-target']:RemoveTargetModel(processedTargetRegistration.models,
            { 'Collect processed scrap' })
    end
    processedTargetRegistration = nil
end

local function registerProcessedTargets()
    removeProcessedTargets()
    local models = {}
    local seen = {}
    for _, recipe in ipairs(ShredderConfig and ShredderConfig.Recipes or {}) do
        local modelName = recipe.outputModel or MODEL_NAMES.chunk
        if recipe.enabled ~= false and not seen[modelName] then
            seen[modelName] = true
            models[#models + 1] = modelName
        end
    end
    if #models == 0 then
        return
    end

    local option = {
        name = 'industrial_shredder_collect_output',
        icon = 'fa-solid fa-recycle',
        label = 'Collect processed scrap',
        distance = ShredderConfig.InteractionDistance or 3.0,
        canInteract = function(entity)
            return DoesEntityExist(entity) and Entity(entity).state.shredderOutputToken ~= nil
        end,
        onSelect = function(data)
            TriggerEvent('vrp-scrap-shredder:client:targetCollectOutput', data)
        end
    }

    if GetResourceState('ox_target') == 'started' then
        exports.ox_target:addModel(models, { option })
        processedTargetRegistration = { provider = 'ox', models = models }
    elseif GetResourceState('qb-target') == 'started' then
        exports['qb-target']:AddTargetModel(models, {
            options = {
                {
                    type = 'client',
                    event = 'vrp-scrap-shredder:client:targetCollectOutput',
                    icon = 'fas fa-recycle',
                    label = 'Collect processed scrap',
                    canInteract = option.canInteract
                }
            },
            distance = ShredderConfig.InteractionDistance or 3.0
        })
        processedTargetRegistration = { provider = 'qb', models = models }
    end
end

CreateThread(function()
    Wait(1800)
    registerProcessedTargets()
end)

local function refreshRecipeTargets()
    local bases = {}
    for base in pairs(targetZones) do bases[#bases + 1] = base end
    for i = 1, #bases do removeTarget(bases[i]) end
    for base in pairs(observedBases) do
        if DoesEntityExist(base) then registerTarget(base) end
    end
    registerProcessedTargets()
end

RegisterNetEvent('vrp-scrap-shredder:client:syncRecipes', function(recipes)
    ShredderConfig.Recipes = type(recipes) == 'table' and recipes or {}
    refreshRecipeTargets()
end)

local creatorPreview
local function cameraDirection(rotation)
    local z, x = math.rad(rotation.z), math.rad(rotation.x)
    local cosX = math.abs(math.cos(x))
    return vector3(-math.sin(z) * cosX, math.cos(z) * cosX, math.sin(x))
end

local function aimedGroundPoint(ignoreEntity)
    local start = GetGameplayCamCoord()
    local direction = cameraDirection(GetGameplayCamRot(2))
    local finish = start + direction * 60.0
    local ray = StartShapeTestRay(start.x, start.y, start.z,
        finish.x, finish.y, finish.z, 511, ignoreEntity or PlayerPedId(), 7)
    local _, hit, hitCoords = GetShapeTestResult(ray)
    local point = hit == 1 and hitCoords or
        GetOffsetFromEntityInWorldCoords(PlayerPedId(), 0.0, 8.0, 0.0)
    local foundGround, groundZ = GetGroundZFor_3dCoord(point.x, point.y, point.z + 50.0, false)
    if foundGround then point = vector3(point.x, point.y, groundZ) end
    return point
end

local function showPlacementHelp()
    BeginTextCommandDisplayHelp('STRING')
    AddTextComponentSubstringPlayerName(
        'Aim to move  |  ~INPUT_COVER~ / ~INPUT_CONTEXT~ rotate  |  ~INPUT_FRONTEND_ACCEPT~ place  |  ~INPUT_FRONTEND_CANCEL~ cancel')
    EndTextCommandDisplayHelp(0, false, true, -1)
end

local function startCreatorPlacement()
    if creatorPreview and DoesEntityExist(creatorPreview) then return end
    local ped = PlayerPedId()
    local point = GetOffsetFromEntityInWorldCoords(ped, 0.0, 8.0, 0.0)
    local heading = GetEntityHeading(ped)
    local base, errorMessage = createAnimatedShredder(point, heading, false, true)
    if not base then notify(errorMessage or 'Could not create the placement preview.') return end
    creatorPreview = base

    CreateThread(function()
        while creatorPreview == base and DoesEntityExist(base) do
            Wait(0)
            DisableControlAction(0, 44, true)
            DisableControlAction(0, 38, true)
            DisableControlAction(0, 191, true)
            DisableControlAction(0, 194, true)
            local speed = IsControlPressed(0, 21) and 110.0 or 42.0
            if IsDisabledControlPressed(0, 44) then heading = (heading + speed * GetFrameTime()) % 360.0 end
            if IsDisabledControlPressed(0, 38) then heading = (heading - speed * GetFrameTime()) % 360.0 end
            local position = aimedGroundPoint(base)
            SetEntityCoordsNoOffset(base, position.x, position.y, position.z, false, false, false)
            SetEntityHeading(base, heading)
            showPlacementHelp()

            if IsDisabledControlJustPressed(0, 191) then
                local final = GetEntityCoords(base)
                creatorPreview = nil
                deleteAnimatedShredder(base)
                TriggerServerEvent('vrp-scrap-shredder:server:savePlacement',
                    { x = final.x, y = final.y, z = final.z }, heading)
                break
            elseif IsDisabledControlJustPressed(0, 194) then
                creatorPreview = nil
                deleteAnimatedShredder(base)
                break
            end
        end
    end)
end

local function editRecipe(recipe)
    local values = lib.inputDialog(recipe and 'Edit Shredder Recipe' or 'Add Shredder Recipe', {
        { type = 'input', label = 'Recipe ID', description = 'Example: car_door', required = true,
            default = recipe and recipe.id or '' },
        { type = 'input', label = 'Display label', required = true,
            default = recipe and recipe.label or '' },
        { type = 'input', label = 'Input item name', required = true,
            default = recipe and recipe.inputItem or '' },
        { type = 'input', label = 'Input prop model', required = true,
            default = recipe and recipe.inputModel or '' },
        { type = 'input', label = 'Output item name', required = true,
            default = recipe and recipe.outputItem or '' },
        { type = 'input', label = 'Output item label', required = true,
            default = recipe and (recipe.outputLabel or recipe.outputItem) or '' },
        { type = 'input', label = 'Output scrap prop model', required = true,
            default = recipe and recipe.outputModel or MODEL_NAMES.chunk },
        { type = 'number', label = 'Minimum output amount', required = true, min = 1, max = 100,
            default = recipe and recipe.outputMin or 1 },
        { type = 'number', label = 'Maximum output amount', required = true, min = 1, max = 100,
            default = recipe and recipe.outputMax or 1 }
    })
    if not values then return end
    TriggerServerEvent('vrp-scrap-shredder:server:saveRecipe', {
        originalId = recipe and recipe.id or nil,
        id = values[1], label = values[2], inputItem = values[3], inputModel = values[4],
        outputItem = values[5], outputLabel = values[6], outputModel = values[7],
        outputMin = values[8], outputMax = values[9]
    })
end

local function openRecipeManager()
    local options = {
        { title = 'Add recipe', icon = 'plus', onSelect = function() editRecipe(nil) end }
    }
    for _, entry in ipairs(ShredderConfig.Recipes or {}) do
        local recipe = entry
        options[#options + 1] = {
            title = recipe.label or recipe.id,
            description = ('%s / %s  →  %s / %s'):format(recipe.inputItem,
                recipe.inputModel, recipe.outputItem, recipe.outputModel),
            icon = 'gears',
            onSelect = function()
                lib.registerContext({ id = 'shredder_recipe_actions',
                    title = recipe.label or recipe.id, menu = 'shredder_recipe_manager', options = {
                        { title = 'Edit recipe', icon = 'pen', onSelect = function() editRecipe(recipe) end },
                        { title = 'Delete recipe', icon = 'trash', onSelect = function()
                            local answer = lib.alertDialog({ header = 'Delete recipe?',
                                content = 'This permanently removes the recipe.', centered = true,
                                cancel = true, labels = { confirm = 'Delete' } })
                            if answer == 'confirm' then
                                TriggerServerEvent('vrp-scrap-shredder:server:deleteRecipe', recipe.id)
                            end
                        end }
                    } })
                lib.showContext('shredder_recipe_actions')
            end
        }
    end
    lib.registerContext({ id = 'shredder_recipe_manager', title = 'Shredder Recipes',
        menu = 'shredder_creator', options = options })
    lib.showContext('shredder_recipe_manager')
end

local function openCreator()
    lib.registerContext({ id = 'shredder_creator', title = 'Industrial Shredder Creator', options = {
        { title = 'Place persistent shredder', description = 'Ghost placement with ground snapping',
            icon = 'industry', onSelect = startCreatorPlacement },
        { title = 'Remove nearest shredder', description = 'Permanently removes the nearest saved machine',
            icon = 'trash', onSelect = function()
                TriggerServerEvent('vrp-scrap-shredder:server:removeNearest')
            end },
        { title = 'Manage processing recipes', description = 'Configure inventory items and prop models',
            icon = 'gears', onSelect = openRecipeManager }
    } })
    lib.showContext('shredder_creator')
end

RegisterNetEvent('vrp-scrap-shredder:client:openCreator', function(recipes)
    if type(recipes) == 'table' then ShredderConfig.Recipes = recipes end
    openCreator()
end)

RegisterNetEvent('vrp-scrap-shredder:client:startPlacement', startCreatorPlacement)

CreateThread(function()
    while true do
        local waitTime = 1000
        local playerCoords = GetEntityCoords(PlayerPedId())
        local deltaTime = GetFrameTime()

        for base, assembly in pairs(assemblies) do
            if not DoesEntityExist(base) then
                safeDelete(assembly.rotorA)
                safeDelete(assembly.rotorB)
                safeDelete(assembly.beltIn)
                safeDelete(assembly.beltOut)
                assemblies[base] = nil
            elseif #(playerCoords - GetEntityCoords(base)) <= 140.0 and
                isShredderEnabled(base) then
                updateAssembly(assembly, deltaTime)
                waitTime = 0
            end
        end

        Wait(waitTime)
    end
end)

-- Discover networked shredder bases created by another client. This keeps the
-- target panel and local-player conveyor response available to every player
-- without running a permanent per-frame world scan.
CreateThread(function()
    while true do
        local nextObserved = {}
        local playerCoords = GetEntityCoords(PlayerPedId())

        for base, assembly in pairs(assemblies) do
            if DoesEntityExist(base) and not assembly.preview then
                nextObserved[base] = true
            end
        end

        for _, object in ipairs(GetGamePool('CObject')) do
            if DoesEntityExist(object) and GetEntityModel(object) == joaat(MODEL_NAMES.base) and
                not (assemblies[object] and assemblies[object].preview) and
                #(playerCoords - GetEntityCoords(object)) <= INTERACTION_RADIUS then
                nextObserved[object] = true
            end
        end

        for base in pairs(nextObserved) do
            registerTarget(base)
        end
        for base in pairs(targetZones) do
            if not nextObserved[base] then
                removeTarget(base)
            end
        end

        observedBases = nextObserved
        Wait(750)
    end
end)

CreateThread(function()
    local nextPropScan = 0

    while true do
        local waitTime = 500
        local now = GetGameTimer()
        local ped = PlayerPedId()
        local playerCoords = GetEntityCoords(ped)
        local activeBases = {}

        for base in pairs(observedBases) do
            if DoesEntityExist(base) and isShredderEnabled(base) and
                #(playerCoords - GetEntityCoords(base)) <= INTERACTION_RADIUS then
                activeBases[#activeBases + 1] = base
            end
        end

        for piece, data in pairs(outputPieces) do
            local expiresAt = type(data) == 'table' and data.expiresAt or data
            if not DoesEntityExist(piece) then
                outputPieces[piece] = nil
            elseif now >= expiresAt then
                if type(data) == 'table' and data.collectible and data.token then
                    TriggerServerEvent('vrp-scrap-shredder:server:cancelOutput', data.token)
                end
                safeDelete(piece)
                outputPieces[piece] = nil
            end
        end

        if #activeBases > 0 then
            waitTime = 50

            if GetConvarInt('vrp_shredder_carry_players', 1) == 1 and not IsEntityDead(ped) then
                for index = 1, #activeBases do
                    local base = activeBases[index]
                    if isOnInputBelt(ped, base) then
                        carryEntity(ped, base, INPUT_ANGLE, 1.42)
                        break
                    elseif isInsideCutterThroat(ped, base) and now >= playerShredCooldown and
                        GetConvarInt('vrp_shredder_player_damage', 1) == 1 then
                        playerShredCooldown = now + 6000
                        spawnOutputPieces(base, GetEntityModel(ped))
                        SetPedToRagdoll(ped, 1800, 1800, 0, false, false, false)
                        SetEntityHealth(ped, 0)
                        break
                    end
                end
            end

            for piece in pairs(outputPieces) do
                for index = 1, #activeBases do
                    local base = activeBases[index]
                    if isOnOutputBelt(piece, base) then
                        carryEntity(piece, base, OUTPUT_ANGLE, 2.15)
                        break
                    end
                end
            end

            if GetConvarInt('vrp_shredder_carry_props', 1) == 1 and now >= nextPropScan then
                nextPropScan = now + PROP_SCAN_INTERVAL
                for _, object in ipairs(GetGamePool('CObject')) do
                    if DoesEntityExist(object) and not MODEL_HASHES[GetEntityModel(object)] and
                        not outputPieces[object] and not IsEntityAttached(object) and
                        Entity(object).state.shredderOutputToken == nil and
                        (Entity(object).state.shredderInputToken == nil or scrapEntities[object]) and
                        (not IsEntityPositionFrozen(object) or IsEntityAMissionEntity(object)) then
                        for index = 1, #activeBases do
                            local base = activeBases[index]
                            if isInsideCutterThroat(object, base) then
                                shredObject(object, base)
                                break
                            elseif isOnInputBelt(object, base) then
                                FreezeEntityPosition(object, false)
                                SetEntityDynamic(object, true)
                                ActivatePhysics(object)
                                carryEntity(object, base, INPUT_ANGLE, 1.32)
                                break
                            end
                        end
                    end
                end
            end
        end

        Wait(waitTime)
    end
end)

CreateThread(function()
    local wasActive = false
    while true do
        local sources = {}
        if ShredderConfig.SoundEnabled ~= false then
            local playerCoords = GetEntityCoords(PlayerPedId())
            local maxDistance = ShredderConfig.SoundMaxDistance or 45.0
            for base in pairs(observedBases) do
                if DoesEntityExist(base) and isShredderEnabled(base) then
                    local distance = #(playerCoords - GetEntityCoords(base))
                    if distance < maxDistance then
                        sources[#sources + 1] = { base = base, distance = distance }
                    end
                end
            end
            table.sort(sources, function(a, b) return a.distance < b.distance end)
            local limit = math.max(1, ShredderConfig.MaxActiveSounds or 3)
            while #sources > limit do table.remove(sources) end
            for _, sourceData in ipairs(sources) do
                local falloff = 1.0 - (sourceData.distance / maxDistance)
                sourceData.id = tostring(sourceData.base)
                sourceData.volume = (ShredderConfig.SoundVolume or 0.42) * falloff * falloff
                sourceData.base, sourceData.distance = nil, nil
            end
        end

        if #sources > 0 or wasActive then
            SendNUIMessage({ action = 'syncShredderAudio', sources = sources })
        end
        wasActive = #sources > 0
        Wait(wasActive and 350 or 1000)
    end
end)

RegisterCommand('spawnshredder', function(_, args)
    if GetConvarInt('vrp_shredder_test_commands', 1) ~= 1 then
        notify('Shredder test commands are disabled.')
        return
    end

    if spawnedTestBase then
        deleteAnimatedShredder(spawnedTestBase)
    end

    local ped = PlayerPedId()
    local position = GetOffsetFromEntityInWorldCoords(ped, 0.0, 8.0, 0.0)
    local heading = tonumber(args[1]) or GetEntityHeading(ped)
    local base, errorMessage = createAnimatedShredder(position, heading, true)

    if not base then
        notify(errorMessage or 'The shredder could not be created.')
        return
    end

    spawnedTestBase = base
    local coords = GetEntityCoords(base)
    print(('[industrial-scrap-shredder] Spawned at vector4(%.4f, %.4f, %.4f, %.4f)')
        :format(coords.x, coords.y, coords.z, GetEntityHeading(base)))
    notify('Animated shredder spawned. Use ~y~/deleteshredder~s~ to remove it.')
end, false)

RegisterCommand('deleteshredder', function()
    if not spawnedTestBase or not DoesEntityExist(spawnedTestBase) then
        notify('There is no test shredder to delete.')
        return
    end

    deleteAnimatedShredder(spawnedTestBase)
    notify('Test shredder removed.')
end, false)

-- Fallback for servers without ox_target or qb-target.
RegisterCommand('toggleshredder', function()
    local playerCoords = GetEntityCoords(PlayerPedId())
    local closestBase
    local closestDistance = 4.0
    for base in pairs(observedBases) do
        if DoesEntityExist(base) then
            local distance = #(playerCoords - GetEntityCoords(base))
            if distance < closestDistance then
                closestBase = base
                closestDistance = distance
            end
        end
    end

    if closestBase then
        toggleShredder(closestBase)
    else
        notify('Stand near the shredder control panel first.')
    end
end, false)

AddEventHandler('onResourceStop', function(resourceName)
    if resourceName ~= GetCurrentResourceName() then
        return
    end

    local bases = {}
    for base in pairs(assemblies) do
        bases[#bases + 1] = base
    end
    for i = 1, #bases do
        deleteAnimatedShredder(bases[i])
    end

    local zones = {}
    for base in pairs(targetZones) do
        zones[#zones + 1] = base
    end
    for i = 1, #zones do
        removeTarget(zones[i])
    end

    for piece in pairs(outputPieces) do
        safeDelete(piece)
    end

    for object in pairs(scrapEntities) do
        safeDelete(object)
    end

    if processedTargetRegistration then
        if processedTargetRegistration.provider == 'ox' and
            GetResourceState('ox_target') == 'started' then
            exports.ox_target:removeModel(processedTargetRegistration.models,
                { 'industrial_shredder_collect_output' })
        elseif processedTargetRegistration.provider == 'qb' and
            GetResourceState('qb-target') == 'started' then
            exports['qb-target']:RemoveTargetModel(processedTargetRegistration.models,
                { 'Collect processed scrap' })
        end
    end
end)
