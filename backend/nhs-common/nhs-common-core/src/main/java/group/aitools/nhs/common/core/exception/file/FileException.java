package group.aitools.nhs.common.core.exception.file;

import group.aitools.nhs.common.core.exception.base.BaseException;

import java.io.Serial;

/**
 * 文件信息异常类
 *
 * @author nhs
 */
public class FileException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 构造文件异常。
     *
     * @param code 错误码
     * @param args 错误码参数
     */
    public FileException(String code, Object[] args) {
        super("file", code, args, null);
    }

}
