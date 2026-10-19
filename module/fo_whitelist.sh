#!/system/bin/sh
# 为"游戏魔盒里添加的应用 + 已安装第三方应用"补齐超分/插帧/维度白名单（可重复执行）
export PATH=/system/bin:/vendor/bin:/product/bin:$PATH
B=/data/user/0/com.vivo.gamecube/databases
TMP=/data/local/tmp/fo_wl
mkdir -p $TMP

q() { nsenter -t 1 -m -- sqlite3 "$1" "$2"; }
cnt() { q "$1" "select count(*) from $2 where pkg_name='$3'"; }

q "$B/game_cube.db" "select package_name from supportGamesTable union select package_name from operatedSupportGamesTable" > $TMP/t.txt
pm list packages -3 2>/dev/null | sed 's/package://' >> $TMP/t.txt
sort -u $TMP/t.txt -o $TMP/t.txt

: > $TMP/sr.sql; : > $TMP/fi.sql; : > $TMP/dm.sql; : > $TMP/gc.sql
BOOST='[{"a":60,"b":48,"c":144},{"a":90,"b":72,"c":144},{"a":120,"b":60,"c":120}]'
OPTI='[{"a":60,"b":30,"c":60},{"a":90,"b":45,"c":90},{"a":120,"b":60,"c":120}]'
TIP='{"60":{"tipStringName":"zhen_60","tipString":"60fps"}}'

while read -r p; do
  [ -z "$p" ] && continue
  if [ "$(cnt "$B/super_resolution_list.db" super_resolution_support_game "$p")" = "0" ]; then
    cat >> $TMP/sr.sql <<SQL
INSERT INTO super_resolution_support_game(pkg_name,label,channel,state,frame_depress,frame_inter,monster_frame_depress,monster_frame_inter,over_heat_temperature,cool_down_temperature,over_heat_temperature_extreme,cool_down_temperature_extreme,game_index,high_frame_depress,high_frame_inter,monster_plus_state,ultra_resolution) VALUES('$p','$p','#',2,30,60,-1,-1,-1.0,-1.0,47.0,44.0,3,-1,-1,0,'2K');
SQL
  fi
  if [ "$(cnt "$B/frame_inter_list.db" frame_inter_support_game "$p")" = "0" ]; then
    cat >> $TMP/fi.sql <<SQL
INSERT INTO frame_inter_support_game(pkg_name,label,channel,game_index,support_sdk,monster_plus_state,over_heat_temperature,cool_down_temperature,temperature_type,boost_frame_values,opti_power_values,frame_rate_tip) VALUES('$p','$p','#',3,0,0,-1.0,-1.0,0,'$BOOST','$OPTI','$TIP');
SQL
  fi
  if [ "$(cnt "$B/support_dimension_list.db" game_dimen "$p")" = "0" ]; then
    for f in competition_mode super_resolution boost_frame optimize_power boost_frame_v3_60_120 boost_frame_v3_60_48_144 game_filter_for_iris game_super_hd_engine screen_light_effect; do
      echo "INSERT INTO game_dimen(function,type,pkg_name,label,channel,oversea,game_index,support_sdk,frame_inter_value) VALUES('$f','white','$p','$p','*',0,-1,0,-1);" >> $TMP/dm.sql
    done
  fi
  if [ "$(q "$B/game_cube.db" "select count(*) from funcTable where package_name='$p'")" = "0" ]; then
    echo "INSERT INTO funcTable(package_name,change_voice,count_down,count_down_type,framework_hook,gamewatch_hook,competition_mode,super_audio_effects,super_audio_type,data_1,voice_command,back_record,double_play,double_pkg_name,double_pkg_main_body,death_replay) VALUES('$p','*','','','','','*','#','7','','*','','','','true','*');" >> $TMP/gc.sql
  fi
done < $TMP/t.txt

[ -s $TMP/sr.sql ] && nsenter -t 1 -m -- sqlite3 "$B/super_resolution_list.db" < $TMP/sr.sql
[ -s $TMP/fi.sql ] && nsenter -t 1 -m -- sqlite3 "$B/frame_inter_list.db" < $TMP/fi.sql
[ -s $TMP/dm.sql ] && nsenter -t 1 -m -- sqlite3 "$B/support_dimension_list.db" < $TMP/dm.sql
[ -s $TMP/gc.sql ] && nsenter -t 1 -m -- sqlite3 "$B/game_cube.db" < $TMP/gc.sql
echo "whitelist ok: SR=$(q "$B/super_resolution_list.db" 'select count(*) from super_resolution_support_game') FI=$(q "$B/frame_inter_list.db" 'select count(*) from frame_inter_support_game') DIMEN=$(q "$B/support_dimension_list.db" 'select count(*) from game_dimen')"
