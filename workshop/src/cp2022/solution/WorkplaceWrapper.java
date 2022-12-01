package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;

public class WorkplaceWrapper extends Workplace {
    private final Workplace originalWorkplace;

    protected WorkplaceWrapper(WorkplaceId id, Workplace original) {
        super(id);
        this.originalWorkplace = original;
    }

    @Override
    public void use() {
    }

    public WorkplaceId getIdName() {
        return originalWorkplace.getId();
    }
}
