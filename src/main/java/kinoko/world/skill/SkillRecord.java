package kinoko.world.skill;

import java.time.Instant;

public final class SkillRecord {
    private final int skillId;
    private int skillLevel;
    private int masterLevel;

    public SkillRecord(int skillId) {
        this.skillId = skillId;
    }

    public SkillRecord(int skillId, int skillLevel) {
        this.skillId = skillId;
        this.skillLevel = skillLevel;
        this.masterLevel = 0;
    }

    public int getSkillId() {
        return skillId;
    }

    public int getSkillLevel() {
        return skillLevel;
    }

    public void setSkillLevel(int skillLevel) {
        this.skillLevel = skillLevel;
    }

    public int getMasterLevel() {
        return masterLevel;
    }

    public void setMasterLevel(int masterLevel) {
        this.masterLevel = masterLevel;
    }

}
