package kinoko.server.dialog.shop;

import kinoko.packet.field.FieldPacket;
import kinoko.packet.stage.CashShopPacket;
import kinoko.packet.world.WvsContext;
import kinoko.provider.ItemProvider;
import kinoko.provider.ShopProvider;
import kinoko.provider.item.ItemInfo;
import kinoko.provider.npc.NpcTemplate;
import kinoko.provider.skill.SkillStat;
import kinoko.server.cashshop.CashItemFailReason;
import kinoko.server.cashshop.CashItemResultType;
import kinoko.server.dialog.Dialog;
import kinoko.server.packet.InPacket;
import kinoko.server.packet.OutPacket;
import kinoko.world.GameConstants;
import kinoko.world.item.*;
import kinoko.world.job.cygnus.NightWalker;
import kinoko.world.job.explorer.Pirate;
import kinoko.world.job.explorer.Thief;
import kinoko.world.user.User;
import kinoko.world.user.stat.Stat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;

public final class ShopDialog implements Dialog {
    private static final Logger log = LogManager.getLogger(ShopDialog.class);
    private final NpcTemplate npcTemplate;
    private final List<ShopItem> items;

    public ShopDialog(NpcTemplate npcTemplate, List<ShopItem> items) {
        this.npcTemplate = npcTemplate;
        this.items = items;
    }

    public void handlePacket(User user, InPacket inPacket) {
        final int type = inPacket.decodeByte();
        final ShopRequestType requestType = ShopRequestType.getByValue(type);
        if (requestType == null) {
            log.error("Unknown shop request type {}", type);
            return;
        }
        switch (requestType) {
            case Buy -> {
                final int index = inPacket.decodeShort(); // nBuySelected
                final int itemId = inPacket.decodeInt(); // nItemID
                final int count = inPacket.decodeShort(); // nCount
                final int price = inPacket.decodeInt(); // DiscountPrice
                // Check buy request matches with shop data
                if (index >= items.size()) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final ShopItem shopItem = items.get(index);
                if (shopItem.getItemId() != itemId || shopItem.getMaxPerSlot() < count || shopItem.getPrice() != price) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                // Check if user has enough money
                final long totalPrice = ((long) price) * count;
                if (totalPrice > GameConstants.MONEY_MAX) {
                    user.write(FieldPacket.shopResult(ShopResultType.BuyNoMoney)); // You do not have enough mesos.
                    return;
                }
                final InventoryManager im = user.getInventoryManager();
                if (!im.canAddMoney((int) -totalPrice)) {
                    user.write(FieldPacket.shopResult(ShopResultType.BuyNoMoney)); // You do not have enough mesos.
                    return;
                }
                // Check if user can add item to inventory
                final int totalQuantity = shopItem.getQuantity() * count;
                if (!im.canAddItem(itemId, totalQuantity)) {
                    user.write(FieldPacket.shopResult(ShopResultType.BuyUnknown)); // Please check if your inventory is full or not.
                    return;
                }
                // Create item
                final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(itemId);
                if (itemInfoResult.isEmpty()) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final ItemInfo ii = itemInfoResult.get();
                final Item boughtItem = ii.createItem(user.getNextItemSn(), totalQuantity);
                // Deduct money and add item to inventory
                if (!im.addMoney((int) -totalPrice)) {
                    throw new IllegalStateException("Could not deduct total price from user");
                }
                final Optional<List<InventoryOperation>> addResult = im.addItem(boughtItem);
                if (addResult.isEmpty()) {
                    throw new IllegalStateException("Could not add bought item to inventory");
                }
                // Update client
                user.write(WvsContext.statChanged(Stat.MONEY, im.getMoney(), false));
                user.write(WvsContext.inventoryOperation(addResult.get(), true));
                user.write(FieldPacket.shopResult(ShopResultType.BuySuccess));
            }
            case Sell -> {
                final int position = inPacket.decodeShort(); // nPOS
                final int itemId = inPacket.decodeInt(); // nItemID
                final int count = inPacket.decodeShort(); // nCount
                final boolean rechargeable = ItemConstants.isRechargeableItem(itemId);
                // Check if sell request possible
                final InventoryManager im = user.getInventoryManager();
                final Inventory inventory = im.getInventoryByItemId(itemId);
                final Item sellItem = inventory.getItem(position);
                if (sellItem.getItemId() != itemId || (rechargeable && count != 1) || (!rechargeable && sellItem.getQuantity() < count)) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                // Resolve sell price
                final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(itemId);
                if (itemInfoResult.isEmpty()) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final ItemInfo ii = itemInfoResult.get();
                final int price = ii.getPrice();
                final long totalPrice = ((long) price) * count + (long) (rechargeable ? Math.ceil(sellItem.getQuantity() * ii.getUnitPrice()) : 0);
                // Check if user can add money
                if (!im.canAddMoney((int) totalPrice)) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg, "You cannot hold any more mesos."));
                    return;
                }
                // Remove items and add money
                final Optional<InventoryOperation> removeItemResult = im.removeItem(position, sellItem, rechargeable ? sellItem.getQuantity() : count);
                if (removeItemResult.isEmpty()) {
                    throw new IllegalStateException("Could not remove item from inventory");
                }
                if (!im.addMoney((int) totalPrice)) {
                    throw new IllegalStateException("Could not add money to inventory");
                }
                // Update client
                user.write(WvsContext.inventoryOperation(removeItemResult.get(), false));
                user.write(WvsContext.statChanged(Stat.MONEY, im.getMoney(), true));
                user.write(FieldPacket.shopResult(ShopResultType.SellSuccess));
            }
            case Recharge -> {
                final int position = inPacket.decodeShort(); // nPos
                final InventoryManager im = user.getInventoryManager();
                final Item item = im.getConsumeInventory().getItem(position);
                // Check if item is rechargeable
                if (!ItemConstants.isRechargeableItem(item.getItemId())) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final Optional<ShopItem> shopItemResult = items.stream()
                        .filter((shopitem) -> shopitem.getItemId() == item.getItemId() && shopitem.getUnitPrice() > 0)
                        .findFirst();
                if (shopItemResult.isEmpty()) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final ShopItem shopitem = shopItemResult.get();
                // Resolve item slot max
                final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(item.getItemId());
                if (itemInfoResult.isEmpty()) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                final int slotMax = itemInfoResult.get().getSlotMax() + getIncSlotMax(user, item.getItemId());
                if (item.getQuantity() >= slotMax) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg)); // Due to an error, the trade did not happen.
                    return;
                }
                // Compute price and check if user has enough money
                final int delta = slotMax - item.getQuantity();
                final long totalPrice = (long) Math.ceil(delta * shopitem.getUnitPrice());
                if (totalPrice > GameConstants.MONEY_MAX) {
                    user.write(FieldPacket.shopResult(ShopResultType.RechargeNoMoney)); // You do not have enough mesos.
                    return;
                }
                if (!im.canAddMoney((int) -totalPrice)) {
                    user.write(FieldPacket.shopResult(ShopResultType.RechargeNoMoney)); // You do not have enough mesos.
                    return;
                }
                // Deduct money and recharge item
                if (!im.addMoney((int) -totalPrice)) {
                    throw new IllegalStateException("Could not deduct total price from user");
                }
                item.setQuantity((short) slotMax);
                // Update client
                user.write(WvsContext.statChanged(Stat.MONEY, im.getMoney(), false));
                user.write(WvsContext.inventoryOperation(InventoryOperation.itemNumber(InventoryType.CONSUME, position, item.getQuantity()), true));
                user.write(FieldPacket.shopResult(ShopResultType.RechargeSuccess));
            }
            case BatchSell -> {
                final int count = inPacket.decodeInt(); // nCount
                final InventoryManager im = user.getInventoryManager();
                // 记录状态
                boolean anySuccess = false;
                boolean hasError = false;
                String lastErrorMessage = null;
                // 开始循环处理
                for (int i = 0; i < count; i++){
                    final InventoryType inventoryType = InventoryType.getByValue(inPacket.decodeShort());
                    final int position = inPacket.decodeInt(); // nPOS
                    // 背包类型校验
                    if (inventoryType == null || inventoryType == InventoryType.EQUIPPED) {
                        hasError = true;
                        lastErrorMessage = "错误的背包类型";
                        continue;
                    }
                    final Inventory inventory = im.getInventoryByType(inventoryType);
                    final Item sellItem = inventory.getItem(position);
                    // 背包道具位置校验
                    if(sellItem == null){
                        hasError = true;
                        lastErrorMessage = "错误的背包位置";
                        continue;
                    }
                    final int itemId = sellItem.getItemId();
                    final boolean rechargeable = ItemConstants.isRechargeableItem(itemId);
                    // 道具存在校验
                    final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(itemId);
                    if (itemInfoResult.isEmpty()) {
                        hasError = true;
                        lastErrorMessage = "不存在的道具";
                        continue;
                    }
                    final ItemInfo ii = itemInfoResult.get();
                    final int price = ii.getPrice();
                    final int sellCount = rechargeable ? 1 : sellItem.getQuantity();
                    final long itemTotalPrice = ((long) price) * sellCount + (long) (rechargeable ? Math.ceil(sellItem.getQuantity() * ii.getUnitPrice()) : 0);
                    // 金币上限校验
                    if (!im.canAddMoney((int) itemTotalPrice)) {
                        hasError = true;
                        lastErrorMessage = "金币数量达到上限";
                        continue;
                    }
                    // 执行移除道具
                    final Optional<InventoryOperation> removeItemResult = im.removeItem(position, sellItem, sellItem.getQuantity());
                    if (removeItemResult.isEmpty()) {
                        hasError = true;
                        lastErrorMessage = "因为未知错误，道具出售失败";
                        continue;
                    }
                    // 增加金币
                    if (!im.addMoney((int) itemTotalPrice)) {
                        hasError = true;
                        lastErrorMessage = "金币数量达到上限";
                        continue;
                    }
                    user.write(WvsContext.inventoryOperation(removeItemResult.get(), false));
                    anySuccess = true;
                }
                if (anySuccess) {
                    user.write(WvsContext.statChanged(Stat.MONEY, im.getMoney(), true));
                }
                if (hasError) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg, lastErrorMessage));
                } else if (anySuccess) {
                    user.write(FieldPacket.shopResult(ShopResultType.SellSuccess));
                }
            }
            case BatchRecharge -> {
                final int count = inPacket.decodeInt(); // nCount
                final InventoryManager im = user.getInventoryManager();
                final Inventory consumeInventory = im.getConsumeInventory();
                // 状态记录
                boolean anySuccess = false;
                boolean hasError = false;
                String lastErrorMessage = null;
                // 开始循环处理
                for (int i = 0; i < count; i++) {
                    final int position = inPacket.decodeInt(); // nPOS
                    final Item item = consumeInventory.getItem(position);
                    // 道具位置校验
                    if (item == null) {
                        hasError = true;
                        lastErrorMessage = "错误的背包位置";
                        continue;
                    }
                    // 可充值属性校验
                    if (!ItemConstants.isRechargeableItem(item.getItemId())) {
                        hasError = true;
                        lastErrorMessage = "该道具无法充值";
                        continue;
                    }
                    // 校验商店是否提供该道具充值
                    final Optional<ShopItem> shopItemResult = items.stream()
                            .filter((shopitem) -> shopitem.getItemId() == item.getItemId() && shopitem.getUnitPrice() > 0)
                            .findFirst();
                    if (shopItemResult.isEmpty()) {
                        hasError = true;
                        lastErrorMessage = "未知错误，该道具无法充值";
                        continue;
                    }
                    final ShopItem shopitem = shopItemResult.get();
                    // 获取道具信息
                    final Optional<ItemInfo> itemInfoResult = ItemProvider.getItemInfo(item.getItemId());
                    if (itemInfoResult.isEmpty()) {
                        hasError = true;
                        lastErrorMessage = "不存在的道具信息";
                        continue;
                    }
                    // 获取道具充值上限
                    final int slotMax = itemInfoResult.get().getSlotMax() + getIncSlotMax(user, item.getItemId());
                    if (item.getQuantity() >= slotMax) {
                        hasError = true;
                        lastErrorMessage = "道具已满，无需充值";
                        continue;
                    }
                    // 计算差价并校验金币
                    final int delta = slotMax - item.getQuantity();
                    final long totalPrice = (long) Math.ceil(delta * shopitem.getUnitPrice());
                    // 金币不足校验
                    if (totalPrice > GameConstants.MONEY_MAX || !im.canAddMoney((int) -totalPrice)) {
                        hasError = true;
                        lastErrorMessage = "金币不足，无法完成全部充值";
                        continue;
                    }
                    // 执行扣除金币
                    if (!im.addMoney((int) -totalPrice)) {
                        hasError = true;
                        lastErrorMessage = "金币不足，充值失败";
                        continue;
                    }
                    // 充值道具
                    item.setQuantity((short) slotMax);
                    user.write(WvsContext.inventoryOperation(InventoryOperation.itemNumber(InventoryType.CONSUME, position, item.getQuantity()), false));
                    anySuccess = true;
                }
                if (anySuccess) {
                    user.write(WvsContext.statChanged(Stat.MONEY, im.getMoney(), false));
                }
                if (hasError) {
                    user.write(FieldPacket.shopResult(ShopResultType.ServerMsg, lastErrorMessage));
                } else if (anySuccess) {
                    user.write(FieldPacket.shopResult(ShopResultType.RechargeSuccess));
                }
            }
            case Close -> {
                user.setDialog(null);
            }
        }
    }

    public void encode(OutPacket outPacket, User user) {
        // CShopDlg::SetShopDlg
        outPacket.encodeInt(npcTemplate.getId()); // dwNpcTemplateID
        outPacket.encodeShort(items.size()); // nCount
        for (ShopItem item : items) {
            outPacket.encodeInt(item.getItemId()); // nItemID
            outPacket.encodeInt(item.getPrice()); // nPrice
            outPacket.encodeByte(0); // nDiscountRate
            outPacket.encodeInt(item.getTokenItemId()); // nTokenItemID
            outPacket.encodeInt(item.getTokenPrice()); // nTokenPrice
            outPacket.encodeInt(0); // nItemPeriod
            outPacket.encodeInt(0); // nLevelLimited
            if (ItemConstants.isRechargeableItem(item.getItemId())) {
                outPacket.encodeDouble(item.getUnitPrice()); // dUnitPrice
                outPacket.encodeShort(item.getMaxPerSlot() + getIncSlotMax(user, item.getItemId())); // nMaxPerSlot
            } else {
                outPacket.encodeShort(item.getQuantity()); // nQuantity
                outPacket.encodeShort(item.getMaxPerSlot()); // nMaxPerSlot
            }
        }
    }

    public static ShopDialog from(NpcTemplate npcTemplate) {
        final List<ShopItem> items = ShopProvider.getNpcShopItems(npcTemplate.getId());
        return new ShopDialog(npcTemplate, items);
    }

    private static int getIncSlotMax(User user, int itemId) {
        int skillId = 0;
        if (ItemConstants.isJavelinItem(itemId)) {
            if (user.getSkillLevel(Thief.CLAW_MASTERY) > 0) {
                skillId = Thief.CLAW_MASTERY;
            } else if (user.getSkillLevel(NightWalker.CLAW_MASTERY) > 0) {
                skillId = NightWalker.CLAW_MASTERY;
            }
        } else if (ItemConstants.isPelletItem(itemId)) {
            if (user.getSkillLevel(Pirate.GUN_MASTERY) > 0) {
                skillId = Pirate.GUN_MASTERY;
            }
        }
        return user.getSkillStatValue(skillId, SkillStat.y);
    }
}
