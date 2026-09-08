local SHREDDER_MODEL = joaat('industrial_scrap_shredder_v23')

RegisterNetEvent('vrp-scrap-shredder:server:toggle', function(netId)
    local playerId = source
    if type(netId) ~= 'number' then
        return
    end

    local shredder = NetworkGetEntityFromNetworkId(netId)
    if shredder == 0 or not DoesEntityExist(shredder) or
        GetEntityModel(shredder) ~= SHREDDER_MODEL then
        return
    end

    local ped = GetPlayerPed(playerId)
    if ped == 0 or #(GetEntityCoords(ped) - GetEntityCoords(shredder)) > 5.0 then
        return
    end

    local enabled = Entity(shredder).state.shredderEnabled ~= true
    Entity(shredder).state:set('shredderEnabled', enabled, true)
    TriggerClientEvent('vrp-scrap-shredder:client:toggleResult', playerId, netId, enabled)
end)
