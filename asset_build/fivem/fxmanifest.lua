fx_version 'cerulean'
game 'gta5'

author 'Vitality Roleplay'
description 'Animated industrial scrap shredder prop'
version '2.5.2'
-- Cache-safe geometry rebuild: industrial_scrap_shredder_v26 model namespace.

shared_scripts {
    '@ox_lib/init.lua',
    'config.lua'
}

client_script 'client.lua'
server_script 'server.lua'

ui_page 'html/index.html'

files {
    'stream/industrial_scrap_shredder_v26.ytyp',
    'html/index.html',
    'html/app.js',
    'html/shredder_loop.ogg'
}

data_file 'DLC_ITYP_REQUEST' 'stream/industrial_scrap_shredder_v26.ytyp'

dependencies {
    'ox_lib',
    'ox_inventory'
}
