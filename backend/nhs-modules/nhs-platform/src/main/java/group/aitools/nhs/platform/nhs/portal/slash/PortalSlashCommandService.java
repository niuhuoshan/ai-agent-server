package group.aitools.nhs.platform.nhs.portal.slash;

import group.aitools.nhs.platform.common.PlatformIdGenerator;
import group.aitools.nhs.platform.iam.domain.CurrentPrincipal;
import group.aitools.nhs.platform.iam.domain.PlatformRole;
import group.aitools.nhs.platform.iam.service.CurrentPrincipalProvider;
import group.aitools.nhs.common.core.constant.HttpStatus;
import group.aitools.nhs.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 负责门户Slash命令相关的业务编排与领域规则处理。
 * Validates and persists Nhs portal slash commands. */
@Service
public class PortalSlashCommandService {

    private final CurrentPrincipalProvider principalProvider;
    private final PlatformIdGenerator idGenerator;
    private final PortalSlashCommandMapper mapper;

    public PortalSlashCommandService(
        CurrentPrincipalProvider principalProvider,
        PlatformIdGenerator idGenerator,
        PortalSlashCommandMapper mapper
    ) {
        this.principalProvider = principalProvider;
        this.idGenerator = idGenerator;
        this.mapper = mapper;
    }

    /**
     * 查询{@code list}列表。
     *
     * @param limit 数量上限
     * @return 符合条件的数据集合
     */
    public List<PortalSlashCommand> list(int limit) {
        CurrentPrincipal principal = human();
        return mapper.selectVisible(principal.id(), isAdmin(principal), Math.min(Math.max(limit, 1), 500));
    }

    /**
     * 创建并保存{@code create}。
     *
     * @param label {@code label}参数
     * @param command 命令参数
     * @param sortOrder {@code sortOrder}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PortalSlashCommand create(String label, String command, int sortOrder) {
        CurrentPrincipal principal = human();
        PortalSlashCommand row = new PortalSlashCommand();
        row.setId(idGenerator.nextId());
        row.setLabel(text(label, 128, "快捷指令名称"));
        row.setCommand(command(command));
        row.setSortOrder(sortOrder);
        row.setCreatedBy(principal.id());
        row.setCreatedAt(LocalDateTime.now());
        row.setDelFlag("0");
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException exception) {
            throw new ServiceException("快捷指令保存失败：标识已存在", HttpStatus.CONFLICT);
        }
        return row;
    }

    /**
     * 更新{@code update}。
     *
     * @param id 资源标识
     * @param label {@code label}参数
     * @param command 命令参数
     * @param sortOrder {@code sortOrder}参数
     * @return 处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public PortalSlashCommand update(Long id, String label, String command, int sortOrder) {
        CurrentPrincipal principal = human();
        String normalizedLabel = text(label, 128, "快捷指令名称");
        String normalizedCommand = command(command);
        if (mapper.update(
            id, normalizedLabel, normalizedCommand, sortOrder, LocalDateTime.now(), principal.id(),
            isAdmin(principal)
        ) != 1) {
            throw new ServiceException("快捷指令不存在或没有管理权限", HttpStatus.FORBIDDEN);
        }
        return require(id);
    }

    /**
     * 删除{@code delete}。
     *
     * @param id 资源标识
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CurrentPrincipal principal = human();
        if (mapper.softDelete(id, LocalDateTime.now(), principal.id(), isAdmin(principal)) != 1) {
            throw new ServiceException("快捷指令不存在或没有删除权限", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 处理{@code reorder}相关逻辑。
     *
     * @param items {@code items}参数
     */
    @Transactional(rollbackFor = Exception.class)
    public void reorder(List<ReorderItem> items) {
        CurrentPrincipal principal = human();
        if (items == null) {
            throw new ServiceException("排序列表不能为空", HttpStatus.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now();
        for (ReorderItem item : items) {
            if (item == null || item.id() == null || item.sortOrder() == null) {
                throw new ServiceException("排序项无效", HttpStatus.BAD_REQUEST);
            }
            mapper.updateSortOrder(
                item.id(), item.sortOrder(), now, principal.id(), isAdmin(principal)
            );
        }
    }

    /**
     * 校验{@code require}，并在条件不满足时终止处理。
     *
     * @param id 资源标识
     * @return 处理结果
     */
    private PortalSlashCommand require(Long id) {
        PortalSlashCommand row = mapper.selectById(id);
        if (row == null) {
            throw new ServiceException("快捷指令不存在", HttpStatus.NOT_FOUND);
        }
        return row;
    }

    /**
     * 处理{@code human}并返回对应结果。
     *
     * @return 处理结果
     */
    private CurrentPrincipal human() {
        CurrentPrincipal principal = principalProvider.currentPrincipal();
        if (!principal.isHuman()) {
            throw new ServiceException("机器主体不能管理门户快捷指令", HttpStatus.FORBIDDEN);
        }
        return principal;
    }

    /**
     * 判断{@code Admin}是否满足要求。
     *
     * @param principal 当前操作主体
     * @return 判断结果，{@code true} 表示条件成立
     */
    private boolean isAdmin(CurrentPrincipal principal) {
        return principal.hasRole(PlatformRole.PLATFORM_ADMIN);
    }

    /**
     * 处理{@code text}并返回对应结果。
     *
     * @param value {@code value}参数
     * @param max {@code max}参数
     * @param label {@code label}参数
     * @return 处理结果
     */
    private String text(String value, int max, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > max || normalized.indexOf('\0') >= 0) {
            throw new ServiceException(label + "为空或超过长度限制", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 处理命令并返回对应结果。
     *
     * @param value {@code value}参数
     * @return 处理结果
     */
    private String command(String value) {
        String normalized = text(value, 2048, "快捷指令");
        if (!normalized.startsWith("/")) {
            throw new ServiceException("快捷指令必须以 / 开头", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 封装{@code ReorderItem}相关的不可变数据。
     */
    public record ReorderItem(Long id, Integer sortOrder) {
    }
}
