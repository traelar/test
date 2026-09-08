local SHREDDER_MODEL = joaat('industrial_scrap_shredder_v26')
local PLACEMENT_KVP = 'persistent_placements_v1'
local RECIPE_KVP = 'persistent_recipes_v1'
local placements, recipes, recipesById, pendingProcesses = {}, {}, {}, {}
local nextPlacementId = 1
local removeNearest

local function notify(playerId, message)
    TriggerClientEvent('vrp-scrap-shredder:client:notify', playerId, message)
end

local function decodeKvp(key, fallback)
    local raw = GetResourceKvpString(key)
    if raw and raw ~= '' then
        local ok, value = pcall(json.decode, raw)
        if ok and type(value) == 'table' then return value end
    end
    return fallback
end

local function rebuildRecipeIndex()
    recipesById = {}
    for _, recipe in ipairs(recipes) do
        if recipe.enabled ~= false and type(recipe.id) == 'string' then
            recipesById[recipe.id] = recipe
        end
    end
end

placements = decodeKvp(PLACEMENT_KVP, {})
recipes = decodeKvp(RECIPE_KVP, ShredderConfig and ShredderConfig.Recipes or {})
for _, placement in ipairs(placements) do
    placement.id = tonumber(placement.id) or nextPlacementId
    placement.enabled = false
    nextPlacementId = math.max(nextPlacementId, placement.id + 1)
end
rebuildRecipeIndex()

local function savePlacements() SetResourceKvp(PLACEMENT_KVP, json.encode(placements)) end
local function saveRecipes()
    SetResourceKvp(RECIPE_KVP, json.encode(recipes))
    rebuildRecipeIndex()
    TriggerClientEvent('vrp-scrap-shredder:client:syncRecipes', -1, recipes)
end

local function findPlacement(id)
    id = tonumber(id)
    for index, placement in ipairs(placements) do
        if placement.id == id then return placement, index end
    end
end

local function isCreatorAdmin(playerId)
    return playerId > 0 and IsPlayerAceAllowed(playerId, 'command.shreddercreator')
end

local function validateMachine(playerId, machineType, machineId, requireEnabled)
    local ped = GetPlayerPed(playerId)
    if ped == 0 then return nil end
    if machineType == 'placement' then
        local placement = findPlacement(machineId)
        if not placement or #(GetEntityCoords(ped) - vector3(
            placement.x, placement.y, placement.z)) > 10.0 then return nil end
        if requireEnabled and placement.enabled ~= true then return nil end
        return placement
    elseif machineType == 'network' then
        local entity = NetworkGetEntityFromNetworkId(tonumber(machineId) or 0)
        if entity == 0 or not DoesEntityExist(entity) or
            GetEntityModel(entity) ~= SHREDDER_MODEL or
            #(GetEntityCoords(ped) - GetEntityCoords(entity)) > 10.0 then return nil end
        if requireEnabled and Entity(entity).state.shredderEnabled ~= true then return nil end
        return entity
    end
end

local function refundInput(process)
    if process and GetResourceState('ox_inventory') == 'started' and
        GetPlayerPing(process.owner) > 0 then
        exports.ox_inventory:AddItem(process.owner, process.recipe.inputItem, 1)
    end
end

local function deleteOutput(process)
    if process and process.outputNetId then
        TriggerClientEvent('vrp-scrap-shredder:client:deleteProcessedOutput',
            -1, process.outputNetId)
    end
end

local function cancelMachineProcesses(machineType, machineId)
    for token, process in pairs(pendingProcesses) do
        if process.machineType == machineType and process.machineId == machineId then
            refundInput(process)
            deleteOutput(process)
            pendingProcesses[token] = nil
        end
    end
end

RegisterNetEvent('vrp-scrap-shredder:server:requestPlacements', function()
    TriggerClientEvent('vrp-scrap-shredder:client:syncPlacements', source, placements)
    TriggerClientEvent('vrp-scrap-shredder:client:syncRecipes', source, recipes)
end)

RegisterCommand('shreddercreator', function(source)
    if source == 0 then
        print('[industrial-scrap-shredder] The creator must be used in game.')
        return
    end
    TriggerClientEvent('vrp-scrap-shredder:client:openCreator', source, recipes)
end, true)

RegisterCommand('placeshredder', function(source)
    if source > 0 then
        TriggerClientEvent('vrp-scrap-shredder:client:startPlacement', source)
    end
end, true)

RegisterCommand('removeshredder', function(source)
    if source > 0 then removeNearest(source) end
end, true)

RegisterNetEvent('vrp-scrap-shredder:server:savePlacement', function(coords, heading)
    local playerId = source
    if not isCreatorAdmin(playerId) or type(coords) ~= 'table' or
        type(coords.x) ~= 'number' or type(coords.y) ~= 'number' or
        type(coords.z) ~= 'number' or type(heading) ~= 'number' then return end
    local ped = GetPlayerPed(playerId)
    if ped == 0 or #(GetEntityCoords(ped) - vector3(coords.x, coords.y, coords.z)) > 60.0 then return end
    local placement = { id = nextPlacementId, x = coords.x, y = coords.y,
        z = coords.z, heading = heading % 360.0, enabled = false }
    nextPlacementId = nextPlacementId + 1
    placements[#placements + 1] = placement
    savePlacements()
    TriggerClientEvent('vrp-scrap-shredder:client:addPlacement', -1, placement)
    notify(playerId, ('Shredder #%d saved permanently.'):format(placement.id))
end)

removeNearest = function(playerId)
    if not isCreatorAdmin(playerId) then return end
    local ped = GetPlayerPed(playerId)
    if ped == 0 then return end
    local playerCoords, closestIndex = GetEntityCoords(ped), nil
    local closestDistance = ShredderConfig.PlacementRemoveDistance or 20.0
    for index, placement in ipairs(placements) do
        local distance = #(playerCoords - vector3(placement.x, placement.y, placement.z))
        if distance < closestDistance then closestIndex, closestDistance = index, distance end
    end
    if not closestIndex then
        notify(playerId, 'No saved shredder is close enough to remove.')
        return
    end
    local removed = table.remove(placements, closestIndex)
    cancelMachineProcesses('placement', removed.id)
    savePlacements()
    TriggerClientEvent('vrp-scrap-shredder:client:removePlacement', -1, removed.id)
    notify(playerId, ('Shredder #%d removed permanently.'):format(removed.id))
end

RegisterNetEvent('vrp-scrap-shredder:server:removeNearest', function()
    removeNearest(source)
end)

RegisterNetEvent('vrp-scrap-shredder:server:saveRecipe', function(recipe)
    local playerId = source
    if not isCreatorAdmin(playerId) or type(recipe) ~= 'table' then return end
    local id = type(recipe.id) == 'string' and recipe.id:lower():gsub('[^%w_]', '_'):sub(1, 40) or ''
    if id == '' or type(recipe.label) ~= 'string' or type(recipe.inputItem) ~= 'string' or
        type(recipe.inputModel) ~= 'string' or type(recipe.outputItem) ~= 'string' or
        type(recipe.outputModel) ~= 'string' then return end
    local cleaned = { id = id, label = recipe.label:sub(1, 60),
        inputItem = recipe.inputItem:sub(1, 60), inputModel = recipe.inputModel:sub(1, 80),
        outputItem = recipe.outputItem:sub(1, 60), outputModel = recipe.outputModel:sub(1, 80),
        outputLabel = tostring(recipe.outputLabel or recipe.outputItem):sub(1, 60),
        outputMin = math.max(1, math.min(100, tonumber(recipe.outputMin) or 1)),
        outputMax = math.max(1, math.min(100, tonumber(recipe.outputMax) or 1)), enabled = true }
    cleaned.outputMax = math.max(cleaned.outputMin, cleaned.outputMax)
    local replaced = false
    local matchId = type(recipe.originalId) == 'string' and recipe.originalId or id
    for index, current in ipairs(recipes) do
        if current.id == matchId then
            recipes[index], replaced = cleaned, true
            break
        end
    end
    if not replaced then recipes[#recipes + 1] = cleaned end
    saveRecipes()
    notify(playerId, ('Recipe "%s" saved.'):format(cleaned.label))
end)

RegisterNetEvent('vrp-scrap-shredder:server:deleteRecipe', function(recipeId)
    local playerId = source
    if not isCreatorAdmin(playerId) then return end
    for index, recipe in ipairs(recipes) do
        if recipe.id == recipeId then
            table.remove(recipes, index)
            saveRecipes()
            notify(playerId, ('Recipe "%s" deleted.'):format(recipe.label or recipe.id))
            return
        end
    end
end)

RegisterNetEvent('vrp-scrap-shredder:server:togglePlacement', function(placementId)
    local placement = findPlacement(placementId)
    if not placement or not validateMachine(source, 'placement', placementId, false) then return end
    placement.enabled = placement.enabled ~= true
    TriggerClientEvent('vrp-scrap-shredder:client:setPlacementState', -1,
        placement.id, placement.enabled)
end)

RegisterNetEvent('vrp-scrap-shredder:server:placeRecipe', function(recipeId, machineType, machineId)
    local playerId, recipe = source, recipesById[recipeId]
    if not recipe or GetResourceState('ox_inventory') ~= 'started' or
        not validateMachine(playerId, machineType, machineId, true) then return end
    local count = exports.ox_inventory:Search(playerId, 'count', recipe.inputItem)
    if not count or count < 1 then
        notify(playerId, ('You do not have %s.'):format(recipe.label or recipe.inputItem))
        return
    end
    if not exports.ox_inventory:RemoveItem(playerId, recipe.inputItem, 1) then return end
    local minimum = math.max(1, tonumber(recipe.outputMin) or 1)
    local maximum = math.max(minimum, tonumber(recipe.outputMax) or minimum)
    local token = ('%d:%d:%06d'):format(playerId, os.time(), math.random(0, 999999))
    pendingProcesses[token] = { owner = playerId, recipe = recipe,
        amount = math.random(minimum, maximum), machineType = machineType,
        machineId = tonumber(machineId), stage = 'input',
        expiresAt = os.time() + (ShredderConfig.PendingProcessTimeoutSeconds or 90) }
    TriggerClientEvent('vrp-scrap-shredder:client:spawnRecipeInput', playerId,
        token, recipe, machineType, machineId)
end)

RegisterNetEvent('vrp-scrap-shredder:server:processComplete', function(token)
    local process = pendingProcesses[token]
    if not process or process.owner ~= source or process.stage ~= 'input' then return end
    process.stage = 'output'
    process.expiresAt = os.time() + (ShredderConfig.OutputCollectTimeoutSeconds or 120)
    TriggerClientEvent('vrp-scrap-shredder:client:spawnProcessedOutput', source,
        token, process.recipe, process.machineType, process.machineId,
        joaat(process.recipe.inputModel))
end)

RegisterNetEvent('vrp-scrap-shredder:server:registerOutput', function(token, netId)
    local process = pendingProcesses[token]
    if not process or process.owner ~= source or process.stage ~= 'output' then return end
    local entity = NetworkGetEntityFromNetworkId(tonumber(netId) or 0)
    if entity ~= 0 and DoesEntityExist(entity) and
        GetEntityModel(entity) == joaat(process.recipe.outputModel) then
        process.outputNetId = tonumber(netId)
    end
end)

RegisterNetEvent('vrp-scrap-shredder:server:collectOutput', function(token, netId)
    local playerId, process = source, pendingProcesses[token]
    if not process or process.stage ~= 'output' or
        GetResourceState('ox_inventory') ~= 'started' then return end
    local entity = NetworkGetEntityFromNetworkId(tonumber(netId) or 0)
    local ped = GetPlayerPed(playerId)
    if entity == 0 or not DoesEntityExist(entity) or ped == 0 or
        GetEntityModel(entity) ~= joaat(process.recipe.outputModel) or
        #(GetEntityCoords(ped) - GetEntityCoords(entity)) > 4.5 or
        (process.outputNetId and process.outputNetId ~= tonumber(netId)) then return end
    if not exports.ox_inventory:AddItem(playerId, process.recipe.outputItem, process.amount) then
        notify(playerId, 'You need more inventory space for the processed scrap.')
        return
    end
    pendingProcesses[token] = nil
    TriggerClientEvent('vrp-scrap-shredder:client:deleteProcessedOutput', -1, netId)
    notify(playerId, ('Collected %dx %s.'):format(process.amount,
        process.recipe.outputLabel or process.recipe.outputItem))
end)

RegisterNetEvent('vrp-scrap-shredder:server:cancelProcess', function(token)
    local process = pendingProcesses[token]
    if process and process.owner == source and process.stage == 'input' then
        refundInput(process)
        pendingProcesses[token] = nil
    end
end)

RegisterNetEvent('vrp-scrap-shredder:server:cancelOutput', function(token)
    local process = pendingProcesses[token]
    if process and process.owner == source then
        refundInput(process)
        deleteOutput(process)
        pendingProcesses[token] = nil
    end
end)

RegisterNetEvent('vrp-scrap-shredder:server:toggle', function(netId)
    local shredder = NetworkGetEntityFromNetworkId(tonumber(netId) or 0)
    local ped = GetPlayerPed(source)
    if shredder == 0 or not DoesEntityExist(shredder) or ped == 0 or
        GetEntityModel(shredder) ~= SHREDDER_MODEL or
        #(GetEntityCoords(ped) - GetEntityCoords(shredder)) > 5.0 then return end
    Entity(shredder).state:set('shredderEnabled',
        Entity(shredder).state.shredderEnabled ~= true, true)
end)

CreateThread(function()
    while true do
        Wait(10000)
        local now = os.time()
        for token, process in pairs(pendingProcesses) do
            if now >= process.expiresAt then
                refundInput(process)
                deleteOutput(process)
                pendingProcesses[token] = nil
            end
        end
    end
end)

AddEventHandler('playerDropped', function()
    for token, process in pairs(pendingProcesses) do
        if process.owner == source then
            deleteOutput(process)
            pendingProcesses[token] = nil
        end
    end
end)

AddEventHandler('onResourceStop', function(resourceName)
    if resourceName ~= GetCurrentResourceName() then return end
    for token, process in pairs(pendingProcesses) do
        refundInput(process)
        deleteOutput(process)
        pendingProcesses[token] = nil
    end
end)
