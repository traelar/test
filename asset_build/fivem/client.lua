local MODEL_NAMES = {
    base = 'vrp_scrap_shredder',
    rotorA = 'vrp_scrap_shredder_rotor_a',
    rotorB = 'vrp_scrap_shredder_rotor_b',
    beltIn = 'vrp_scrap_shredder_belt_in',
    beltOut = 'vrp_scrap_shredder_belt_out'
}

local ROTOR_A_OFFSET = vector3(-0.39, 0.0, 2.72)
local ROTOR_B_OFFSET = vector3(0.39, 0.0, 2.72)
local INPUT_ANGLE = math.rad(-32.0)
local OUTPUT_ANGLE = math.rad(11.0)
local BELT_PITCH = 0.265
local assemblies = {}
local spawnedTestBase

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
    assembly.rotorAngle = (assembly.rotorAngle + (deltaTime * 115.0)) % 360.0
    assembly.inputPhase = (assembly.inputPhase + (deltaTime * 0.72)) % BELT_PITCH
    assembly.outputPhase = (assembly.outputPhase + (deltaTime * 0.55)) % BELT_PITCH

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
            elseif #(playerCoords - GetEntityCoords(base)) <= 140.0 then
                updateAssembly(assembly, deltaTime)
                waitTime = 0
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
end)
