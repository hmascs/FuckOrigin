#!/system/bin/sh
# 为"游戏魔盒里添加的应用"补齐超分/插帧/维度白名单（可重复执行）
export PATH=/system/bin:/vendor/bin:/product/bin:$PATH
B=/data/user/0/com.vivo.gamecube/databases
TMP=/data/local/tmp/fo_wl
mkdir -p $TMP

sql() { nsenter -t 1 -m -- sqlite3 "$1"; }

# 目标：supportGamesTable ∪ operatedSupportGamesTable
sql "$B/game_cube.db" "select package_name from supportGamesTable union select package_name from operatedSupportGamesTable" > $TMP/targets.txt

: > $TMP/sr.sql; : > $TMP/fi.sql; : > $TMP/dm.sql; : > $TMP/gc.sql
while read -r p; do
  [ -z "$p" ] && continue
  n=$(sql "$B/super_resolution_list.db" "select count(*) from super_resolution_support_game where pkg_name='$p'")
  if [ "$n" = "0" ]; then
    echo "INSERT OR IGNORE INTO super_resolution_support_game(pkg_name,label,channel,state,frame_depress,frame_inter,monster_frame_depress,monster_frame_inter,over_heat_temperature,cool_down_temperature,over_heat_temperature_extreme,cool_down_temperature_extreme,game_index,high_frame_depress,high_frame_inter,monster_plus_state,ultra_resolution) VALUES('$p','$p','#',2,30,60,-1,-1,-1.0,-1.0,47.0,44.0,3,-1,-1,0,'2K');" >> $TMP/sr.sql
  fi
  n=$(sql "$B/frame_inter_list.db" "select count(*) from frame_inter_support_game where pkg_name='$p'")
  if [ "$n" = "0" ]; then
    echo "INSERT OR IGNORE INTO frame_inter_support_game(pkg_name,label,channel,game_index,support_sdk,monster_plus_state,over_heat_temperature,cool_down_temperature,temperature_type,boost_frame_values,opti_power_values,frame_rate_tip) VALUES('$p','$p','#',3,0,0,-1.0,-1.0,0,'[{\"a\":60,\"b\":48,\"c\":144}]','[{\"a\":60,\"b\":30,\"c\":60}]','{\"60\":{\"tipStringName\":\"zhen_60\",\"tipString\":\"60fps\"}}');" >> $TMP/fi.sql
  fi
  n=$(sql "$B/support_dimension_list.db" "select count(*) from game_dimen where pkg_name='$p'")
  if [ "$n" = "0" ]; then
    for f in competition_mode super_resolution boost_frame optimize_power boost_frame_v3_60_120 boost_frame_v3_60_48_144 game_filter_for_iris game_super_hd_engine screen_light_effect; do
      echo "INSERT OR IGNORE INTO game_dimen(function,type,pkg_name,label,channel,oversea,game_index,support_sdk,frame_inter_value) VALUES('$f','white','$p','$p','*',0,-1,0,-1);" >> $TMP/dm.sql
    done
  fi
  n=$(sql "$B/game_cube.db" "select count(*) from funcTable where package_name='$p'")
  if [ "$n" = "0" ]; then
    echo "INSERT OR IGNORE INTO funcTable(package_name,change_voice,count_down,count_down_type,framework_hook,gamewatch_hook,competition_mode,super_audio_effects,super_audio_type,data_1,voice_command,back_record,double_play,double_pkg_name,double_pkg_main_body,death_replay) VALUES('$p','*','','','','','*','#','7','','*','','','','true','*');" >> $TMP/gc.sql
  fi
done < $TMP/targets.txt

chmod 644 $TMP/*.sql 2>/dev/null
[ -s $TMP/sr.sql ] && sql "$B/super_resolution_list.db" < $TMP/sr.sql
[ -s $TMP/fi.sql ] && sql "$B/frame_inter_list.db" < $TMP/fi.sql
[ -s $TMP/dm.sql ] && sql "$B/support_dimension_list.db" < $TMP/dm.sql
[ -s $TMP/gc.sql ] && sql "$B/game_cube.db" < $TMP/gc.sql
echo "whitelist done: SR=$(sql "$B/super_resolution_list.db" 'select count(*) from super_resolution_support_game') FI=$(sql "$B/frame_inter_list.db" 'select count(*) from frame_inter_support_game') DIMEN=$(sql "$B/support_dimension_list.db" 'select count(*) from game_dimen')"
