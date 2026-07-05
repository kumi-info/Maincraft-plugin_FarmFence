# FarmFrame

**バージョン: v1.9.0**

s2e-farm-pro（streamtoearn の農場ゲーム）のアリーナ（フロア）を読み取り、その**1マス外側を1マス幅で囲う、農業デザインの枠（柵マスト）**を一発で生成するコマンドプラグインです。TikTok 妨害配信の Farm サーバー向け。配信で枠が壊れても一発で復元できます。

---

## できること

- `/farmfence <番号>` で、アリーナの**ちょうど1マス外側**に農業デザインの枠を生成（柵は必須デザイン）。
- アリーナ座標・サイズは **`plugins/s2e-farm-pro/config.yml` から自動取得**（`arena.location.{x,y,z}` と `arena.{sizeX,sizeZ}`）。座標指定は不要。
- 角には柱＋ランタン、柵の上には一定間隔で明かり、を自動配置。
- `/farmfence clear` で枠を撤去。
- 位置がズレたら `arena.anchor`（center/corner）やオフセットを変えて `/farmfence reload` で即調整（アリーナ内部＝プレイ面には触れないので安全）。
- **破壊耐性（v1.4.0）**: 枠のブロックは**どんな攻撃でも消えません**。`protect.explosion`（TNT/クリーパー）・`protect.fire`（引火・燃焼＝**落雷の着火含む**。干し草/木の柵が燃えない）・`protect.break`（プレイヤーの手壊し・エンダーマン等）をそれぞれ ON/OFF 可（既定すべて true）。アリーナ内部や他の場所は通常どおり壊れます。
- **サイズ自動追従（v1.1.0）**: `auto-follow.enabled: true`（既定）で、`/farm create` 等でアリーナの大きさ・位置が変わると、枠を自動で作り直します（旧枠は枠素材だけを撤去するのでプレイ面・作物を巻き込みません）。
- **自動生成（v1.6.0）**: `auto-follow.auto-build: true`（既定）なら、**枠が無くても** `/farm create` 等で新しいアリーナを検知した時に枠を**自動で建てます**。使うスタイルは直前に建てたもの（無ければ `auto-follow.style`）。`/farmfence clear` した後は、次にアリーナが変わるまで再生成しません。
- **/farm delete 連動撤去（v1.7.0）**: `clear-on-farm-delete: true`（既定）で、`/farm delete` を実行すると枠も一緒に消えます（撤去後は次の `/farm create` まで自動生成しません）。

---

## コマンド

```
/farmfence <1-11|list|clear|reload>
```

| 引数 | 動作 |
|---|---|
| `1`〜`11` | その番号のスタイルで枠を生成 |
| `list` | スタイル一覧を表示 |
| `clear` | 枠を撤去（外周リングを空気に戻す） |
| `reload` | `config.yml` を再読み込み |

- 権限: `farmfence.use`（デフォルト op）
- エイリアス: `/ffence` / `/farmwaku`（v1.9.0 でメインコマンドを `/farmframe` → `/farmfence` に変更。`/ff`・`/fframe` は廃止）
- タブ補完対応

### デフォルトのスタイル（11種）

| 番号 | 名前 | 柵 | 土台 | 角の飾り |
|---|---|---|---|---|
| 1 | 木の牧場 | オークの柵 | 干し草 | ランタン |
| 2 | 白樺ガーデン | 白樺の柵 | 土の道 | ランタン |
| 3 | 暗黒オーク農園 | ダークオークの柵 | ポドゾル | ジャックオランタン |
| 4 | かぼちゃ祭り | オークの柵 | 干し草 | くり抜きカボチャ |
| 5 | サバンナ牧場 | アカシアの柵 | 粗い土 | ランタン |
| 6 | タイガ農園 | トウヒの柵 | ポドゾル | ランタン |
| 7 | ジャングル農園 | ジャングルの柵 | 苔ブロック | ランタン |
| 8 | マングローブ湿地 | マングローブの柵 | 泥 | ランタン |
| 9 | 竹林ガーデン | 竹の柵 | 苔ブロック | ランタン |
| 10 | 収穫祭 | オークの柵 | 干し草 | ジャックオランタン |
| 11 | ニコニコ | オークの柵 | オレンジ色コンクリート | シュルームライト（オレンジ発光） |

---

## 仕組み

- **範囲取得**: `plugins/s2e-farm-pro/config.yml` の `arena.location`(world_key/x/y/z) と `arena.sizeX/sizeZ` を読み、アリーナ範囲を算出。
  - `arena.anchor: corner`（既定）… location をアリーナの**角（最小X/Z）**とみなす（farm は location が角のため）。
  - `arena.anchor: center` … location をアリーナ**中心**とみなす（size=15 なら ±7）。
  - 枠が床より小さく/ズレて見える場合は anchor を切り替えるか、`center-x-offset`/`center-z-offset` で微調整して `/farmfence reload`。
- **枠の位置**: アリーナの `frame.gap`（既定1）マス外側に、1マス幅の外周リングを作る。
- **構成**: 各リングセルに `base`（土台）→ その上に `fence`（柵）。角は `post`（柱）を `post-height` 段＋`top`（飾り）。柵の上には `light.spacing` マスごとに `light`（明かり）。
- **ワールド**: `world` 未指定なら farm の world_key（overworld）= 環境 NORMAL のワールドを自動選択。
- プレイ面（アリーナ内部）には一切触れません。

---

## config.yml（抜粋）

```yaml
source-plugin: s2e-farm-pro
world: ""              # 空で overworld 自動選択
arena:
  anchor: center       # center=中心 / corner=角
  center-x-offset: 0
  center-z-offset: 0
  size-padding: 0
frame:
  gap: 1               # アリーナの何マス外側か
  base-y-offset: 0     # フロアYからの基準オフセット
  corner: { enabled: true, post-height: 2 }
  light:  { enabled: true, spacing: 4 }
styles:
  - name: "木の牧場"
    base: HAY_BLOCK
    fence: OAK_FENCE
    post: OAK_LOG
    top: LANTERN
    light: LANTERN
  # ... 追加・編集可（番号は上からの並び順）
```

---

## インストール / 反映

1. ビルドした `FarmFrame_v1.0.0.jar` を `plugins/` に配置。
2. サーバー再起動（または `/reload confirm`）で有効化。`config.yml` / `README.md` は初回起動時に自動生成。

ビルドは [minecraft-build-env] のオフライン手順（JDK21 + paper-api、Maven不使用）。

---

## 更新履歴

| バージョン | 変更点 |
|---|---|
| v1.9.0 | **メインコマンドを `/farmframe` → `/farmfence` に変更**（`farmframe` が他プラグインと衝突するため）。権限ノードも `farmframe.use` → `farmfence.use`、エイリアスも `ffence` / `farmwaku` に変更。 |
| v1.8.1 | エイリアス `ff` を `fframe` に変更（`ff` は FastFurnace 等の他プラグインと衝突しやすく、`/farmframe:ff` 等にズレるため）。メインコマンド `farmframe` と `farmwaku` は変更なし。 |
| v1.8.0 | 中心モードを**半径モデル**に修正（アリーナ=中心±sizeX で対称・31×31）。`size-scale` 既定を 1.0 に（旧2.0の1マスズレ・非対称を解消）。スタイル11「ニコニコ」を NICO色（ティール#1ab5ba＋イエロー#fbdb58）＋農場テーマに変更。 |
| v1.7.0 | `/farm delete` を検知して枠も自動撤去する `clear-on-farm-delete`（既定ON）を追加。撤去後は次のアリーナ作成まで自動生成しないよう抑制。 |
| v1.6.0 | `/farm create` 等で新しいアリーナを検知したら、枠が無くても自動生成する `auto-follow.auto-build`（既定ON）を追加。直前のスタイルを記憶。追従間隔の既定を2秒に短縮。 |
| v1.5.0 | 辺ごとの外側マス数 `frame.gap-north/south/east/west`（省略時 `gap`）を追加。特定の2辺だけ後ろに広げられる。 |
| v1.4.0 | 枠を**火・落雷の着火・延焼・プレイヤー破壊・エンダーマン等**からも保護（`protect.fire` / `protect.break` 追加）。爆発と合わせ「どんな攻撃にも消えない」枠に。 |
| v1.3.0 | farm の sizeX/sizeZ→実ブロック数の倍率 `arena.size-scale`(既定2.0)を追加（/farm create 15 15 が実際は約30×30のため）。NICOらしいスタイル「ニコニコ」(オレンジ＋シュルームライト発光)を追加し計11種に。 |
| v1.2.0 | スタイルを10種に拡張。既定 anchor を `corner` に変更（枠が床より小さく見える不具合を修正＝farm の location は角基準）。 |
| v1.1.0 | 爆発(TNT/クリーパー)から枠を保護（`protect.explosion`）。アリーナのサイズ/位置変化に自動追従して作り直し（`auto-follow`）。状態を `state.yml` に永続化し、再生成時に旧枠をマテリアル対応で撤去。 |
| v1.0.0 | 初版。s2e-farm-pro のアリーナを自動取得し、1マス外側を囲う農業デザインの枠（柵マスト）を生成。3スタイル＋角柱＋明かり、clear/reload 対応。 |

---

> 📌 **メンテナンス方針**: 機能を変更してバージョンを上げたときは、必ず本 README の「バージョン」表記・コマンド表・更新履歴も合わせて更新すること。
