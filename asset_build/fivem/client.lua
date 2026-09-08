local MODEL_NAMES = {
    base = 'industrial_scrap_shredder_v23',
    rotorA = 'industrial_scrap_shredder_v23_rotor_a',
    rotorB = 'industrial_scrap_shredder_v23_rotor_b',
    beltIn = 'industrial_scrap_shredder_v23_belt_in',
    beltOut = 'industrial_scrap_shredder_v23_belt_out',
    chunk = 'industrial_scrap_shredder_v23_chunk'
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
local playerShredCooldown = 0

local MODEL_HASHES = {}
for _, modelName in pairs(MODEL_NAMES) do
    MODEL_HASHES[joaat(modelName)] = true
end

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
        SetEntityAsMissionEntity(entity, true, true)
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

    if NetworkGetEntityIsNetworked(base) then
        TriggerServerEvent('vrp-scrap-shredder:server:toggle', NetworkGetNetworkIdFromEntity(base))
    else
        local enabled = not isShredderEnabled(base)
        Entity(base).state:set('shredderEnabled', enabled, false)
        notify(enabled and 'Industrial shredder switched ~g~ON~s~.' or
            'Industrial shredder switched ~r~OFF~s~.')
    end
end

RegisterNetEvent('vrp-scrap-shredder:client:toggleResult', function(netId, enabled)
    local base = NetworkGetEntityFromNetworkId(netId)
    if base ~= 0 and DoesEntityExist(base) then
        notify(enabled and 'Industrial shredder switched ~g~ON~s~.' or
            'Industrial shredder switched ~r~OFF~s~.')
    end
end)

RegisterNetEvent('vrp-scrap-shredder:client:targetToggle', function(data)
    toggleShredder(data and (data.base or data.entity))
end)

local function removeTarget(base)
    local zone = targetZones[base]
    if not zone then
        return
    end

    if zone.provider == 'ox' and GetResourceState('ox_target') == 'started' then
        exports.ox_target:removeZone(zone.id)
    elseif zone.provider == 'qb' and GetResourceState('qb-target') == 'started' then
        exports['qb-target']:RemoveZone(zone.id)
    end
    targetZones[base] = nil
end

local function registerTarget(base)
    if targetZones[base] or not DoesEntityExist(base) then
        return
    end

    local center = GetOffsetFromEntityInWorldCoords(base, 1.82, -1.62, 2.04)
    local heading = GetEntityHeading(base)
    local zoneName = ('industrial_shredder_panel_%s'):format(base)

    if GetResourceState('ox_target') == 'started' then
        local zoneId = exports.ox_target:addBoxZone({
            coords = center,
            size = vector3(0.95, 0.75, 1.35),
            rotation = heading,
            debug = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
            options = {
                {
                    name = zoneName,
                    icon = 'fa-solid fa-power-off',
                    label = 'Toggle industrial shredder',
                    distance = 2.0,
                    canInteract = function()
                        return DoesEntityExist(base)
                    end,
                    onSelect = function()
                        toggleShredder(base)
                    end
                }
            }
        })
        targetZones[base] = { provider = 'ox', id = zoneId }
    elseif GetResourceState('qb-target') == 'started' then
        exports['qb-target']:AddBoxZone(zoneName, center, 0.95, 0.75, {
            name = zoneName,
            heading = heading,
            debugPoly = GetConvarInt('vrp_shredder_target_debug', 0) == 1,
            minZ = center.z - 0.68,
            maxZ = center.z + 0.68
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
            distance = 2.0
        })
        targetZones[base] = { provider = 'qb', id = zoneName }
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
                outputPieces[piece] = GetGameTimer() + lifetime
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
    safeDelete(object)
    spawnOutputPieces(base, sourceModel)
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

local function deleteAnimatedShredder(base)
    removeTarget(base)
    local assembly = assemblies[base]
    if not assembly then
        safeDelete(base)
        return
    end

    safeDelete(assembly.rotorA)
    safeDelete(assembly.rotorB)
    safeDelete(assembly.beltIn)
    safeDelete(assembly.beltOut)
    safeDelete(assembly.base)
    assemblies[base] = nil

    if spawnedTestBase == base then
        spawnedTestBase = nil
    end
end

local function createAnimatedShredder(coords, heading, networked)
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
        outputPhase = 0.0
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
    registerTarget(base)
    updateAssembly(assembly, 0.0)
    releaseModels()
    return base
end

exports('CreateAnimatedShredder', createAnimatedShredder)
exports('DeleteAnimatedShredder', deleteAnimatedShredder)

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

        for base in pairs(assemblies) do
            if DoesEntityExist(base) then
                nextObserved[base] = true
            end
        end

        for _, object in ipairs(GetGamePool('CObject')) do
            if DoesEntityExist(object) and GetEntityModel(object) == joaat(MODEL_NAMES.base) and
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

        for piece, expiresAt in pairs(outputPieces) do
            if not DoesEntityExist(piece) then
                outputPieces[piece] = nil
            elseif now >= expiresAt then
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
end)
