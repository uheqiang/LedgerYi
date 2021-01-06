package cn.ledgeryi.contract.vm;

import cn.ledgeryi.chainbase.actuator.VmActuator;
import cn.ledgeryi.chainbase.common.runtime.InternalTransaction;
import cn.ledgeryi.chainbase.common.runtime.ProgramResult;
import cn.ledgeryi.chainbase.common.utils.ContractUtils;
import cn.ledgeryi.chainbase.common.utils.DBConfig;
import cn.ledgeryi.chainbase.core.capsule.AccountCapsule;
import cn.ledgeryi.chainbase.core.capsule.BlockCapsule;
import cn.ledgeryi.chainbase.core.capsule.ContractCapsule;
import cn.ledgeryi.chainbase.core.db.TransactionContext;
import cn.ledgeryi.common.core.exception.ContractExeException;
import cn.ledgeryi.common.core.exception.ContractValidateException;
import cn.ledgeryi.common.logsfilter.trigger.ContractTrigger;
import cn.ledgeryi.common.utils.DecodeUtil;
import cn.ledgeryi.contract.utils.TransactionUtil;
import cn.ledgeryi.contract.vm.config.ConfigLoader;
import cn.ledgeryi.contract.vm.config.VmConfig;
import cn.ledgeryi.contract.vm.program.Program;
import cn.ledgeryi.contract.vm.program.ProgramPrecompile;
import cn.ledgeryi.contract.vm.program.invoke.ProgramInvoke;
import cn.ledgeryi.contract.vm.program.invoke.ProgramInvokeFactory;
import cn.ledgeryi.contract.vm.program.invoke.ProgramInvokeFactoryImpl;
import cn.ledgeryi.contract.vm.repository.Repository;
import cn.ledgeryi.contract.vm.repository.RepositoryImpl;
import cn.ledgeryi.protos.Protocol;
import cn.ledgeryi.protos.contract.SmartContractOuterClass.*;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static cn.ledgeryi.contract.utils.MUtil.transfer;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.apache.commons.lang3.ArrayUtils.isNotEmpty;

@Slf4j(topic = "VM")
public class LedgerYiVmActuator implements VmActuator {

  private VM vm;
  private Program program;
  private BlockCapsule blockCap;
  private Repository repository;
  private Protocol.Transaction tx;
  private LogInfoTriggerParser logInfoTriggerParser;
  private ProgramInvokeFactory programInvokeFactory;
  private VmConfig vmConfig = VmConfig.getInstance();
  private InternalTransaction rootInternalTransaction;
  private InternalTransaction.ExecutorType executorType;

  @Getter
  @Setter
  private InternalTransaction.TxType txType;

  @Getter
  @Setter
  private boolean isConstantCall;

  @Setter
  private boolean enableEventListener;

  public LedgerYiVmActuator(boolean isConstantCall) {
    this.isConstantCall = isConstantCall;
    programInvokeFactory = new ProgramInvokeFactoryImpl();
  }

  @Override
  public void validate(Object object) throws ContractValidateException {
    TransactionContext context = (TransactionContext) object;
    if (Objects.isNull(context)){
      throw new RuntimeException("TransactionContext is null");
    }

    //Load Config
    ConfigLoader.load(context.getStoreFactory());
    tx = context.getTxCap().getInstance();
    blockCap = context.getBlockCap();
    Protocol.Transaction.Contract.ContractType contractType = this.tx.getRawData().getContract().getType();
    //Prepare Repository
    repository = RepositoryImpl.createRoot(context.getStoreFactory());
    enableEventListener = context.isEventPluginLoaded();
    if (Objects.nonNull(blockCap)) {
      this.executorType = InternalTransaction.ExecutorType.ET_NORMAL_TYPE;
    } else {
      this.blockCap = new BlockCapsule(Protocol.Block.newBuilder().build());
      this.executorType = InternalTransaction.ExecutorType.ET_PRE_TYPE;
    }
    if (isConstantCall) {
      this.executorType = InternalTransaction.ExecutorType.ET_PRE_TYPE;
    }

    switch (contractType.getNumber()) {
      case Protocol.Transaction.Contract.ContractType.TriggerSmartContract_VALUE:
        txType = InternalTransaction.TxType.TX_CONTRACT_CALL_TYPE;
        call();
        break;
      case Protocol.Transaction.Contract.ContractType.CreateSmartContract_VALUE:
        txType = InternalTransaction.TxType.TX_CONTRACT_CREATION_TYPE;
        create();
        break;
      default:
        throw new ContractValidateException("Unknown contract type");
    }
  }

  @Override
  public void execute(Object object) {
    TransactionContext context = (TransactionContext) object;
    if (Objects.isNull(context)){
      throw new RuntimeException("TransactionContext is null");
    }
    ProgramResult result = context.getProgramResult();
    try {
      if (vm != null) {
        if (null != blockCap && blockCap.generatedByMyself && blockCap.hasMasterSignature()
            && null != TransactionUtil.getContractRet(tx)
            && Protocol.Transaction.Result.ContractResult.OUT_OF_TIME == TransactionUtil.getContractRet(tx)) {
          result = program.getResult();
          Program.OutOfTimeException e = Program.Exception.alreadyTimeOut();
          result.setRuntimeError(e.getMessage());
          result.setException(e);
          throw e;
        }

        vm.play(program);
        result = program.getResult();

        long cpuTimeCost = program.getCpuTimeCost();
        repository.putCpuTimeConsumeValue(program.getContractAddress().getNoLeadZeroesData(), cpuTimeCost);

        if (isConstantCall) {
          if (result.getException() != null) {
            result.setRuntimeError(result.getException().getMessage());
            result.rejectInternalTransactions();
          }
          context.setProgramResult(result);
          return;
        }

        if (InternalTransaction.TxType.TX_CONTRACT_CREATION_TYPE == txType && !result.isRevert()) {
          byte[] code = program.getResult().getHReturn();
          repository.saveCode(program.getContractAddress().getNoLeadZeroesData(), code);
        }

        if (result.getException() != null || result.isRevert()) {
          result.getDeleteAccounts().clear();
          result.getLogInfoList().clear();
          result.resetFutureRefund();
          result.rejectInternalTransactions();
          if (result.getException() != null) {
            result.setRuntimeError(result.getException().getMessage());
            throw result.getException();
          } else {
            result.setRuntimeError("REVERT opcode executed");
          }
        } else {
          repository.commit();
          if (logInfoTriggerParser != null) {
            List<ContractTrigger> triggers = logInfoTriggerParser.parseLogInfos(program.getResult().getLogInfoList(), repository);
            program.getResult().setTriggerList(triggers);
          }
        }
      } else {
        repository.commit();
      }
    } catch (Program.JVMStackOverFlowException e) {
      result = program.getResult();
      result.setException(e);
      result.rejectInternalTransactions();
      result.setRuntimeError(result.getException().getMessage());
      log.info("jvm stack overflow exception: {}", result.getException().getMessage());
    } catch (Program.OutOfTimeException e) {
      result = program.getResult();
      result.setException(e);
      result.rejectInternalTransactions();
      result.setRuntimeError(result.getException().getMessage());
      log.info("timeout: {}", result.getException().getMessage());
    } catch (Throwable e) {
      result = program.getResult();
      result.rejectInternalTransactions();
      if (Objects.isNull(result.getException())) {
        log.error(e.getMessage(), e);
        result.setException(new RuntimeException("Unknown Throwable"));
      }
      if (StringUtils.isEmpty(result.getRuntimeError())) {
        result.setRuntimeError(result.getException().getMessage());
      }
      log.info("runtime result is :{}", result.getException().getMessage());
    }

    //use program returned fill context
    context.setProgramResult(result);

    if (VmConfig.vmTrace() && program != null) {
      String traceContent = program.getTrace().result(result.getHReturn()).error(result.getException()).toString();
      if (VmConfig.vmTraceCompressed()) {
        traceContent = VMUtils.zipAndEncode(traceContent);
      }
      String txHash = Hex.toHexString(rootInternalTransaction.getHash());
      VMUtils.saveProgramTraceFile(txHash, traceContent);
    }
  }

  private void create() throws ContractValidateException {
    if (!repository.getDynamicPropertiesStore().supportVM()) {
      throw new ContractValidateException("vm work is off, need to be opened by the committee");
    }
    CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(tx);
    if (contract == null) {
      throw new ContractValidateException("Cannot get CreateSmartContract from transaction");
    }
    SmartContract newSmartContract = contract.getNewContract();
    if (!contract.getOwnerAddress().equals(newSmartContract.getOriginAddress())) {
      log.info("OwnerAddress not equals OriginAddress");
      throw new ContractValidateException("OwnerAddress is not equals OriginAddress");
    }
    byte[] contractName = newSmartContract.getName().getBytes();
    if (contractName.length > VMConstant.CONTRACT_NAME_LENGTH) {
      throw new ContractValidateException("contractName's length cannot be greater than 32");
    }
    long percent = contract.getNewContract().getConsumeUserResourcePercent();
    if (percent < 0 || percent > VMConstant.ONE_HUNDRED) {
      throw new ContractValidateException("percent must be >= 0 and <= 100");
    }
    byte[] contractAddress = ContractUtils.generateContractAddress(tx);
    // insure the new contract address haven't exist
    if (repository.getAccount(contractAddress) != null) {
      throw new ContractValidateException("Trying to create a contract with existing contract address: "
              + DecodeUtil.createReadableString(contractAddress));
    }
    newSmartContract = newSmartContract.toBuilder().setContractAddress(ByteString.copyFrom(contractAddress)).build();
    long tokenValue = 0;
    long tokenId = 0;

    byte[] callerAddress = contract.getOwnerAddress().toByteArray();
    // create vm to constructor smart contract
    try {
      long feeLimit = tx.getRawData().getFeeLimit();
      if (feeLimit < 0 || feeLimit > VmConfig.MAX_FEE_LIMIT) {
        log.info("invalid feeLimit {}", feeLimit);
        throw new ContractValidateException("feeLimit must be >= 0 and <= " + VmConfig.MAX_FEE_LIMIT);
      }
      byte[] ops = newSmartContract.getBytecode().toByteArray();
      rootInternalTransaction = new InternalTransaction(tx, txType);
      ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(InternalTransaction.TxType.TX_CONTRACT_CREATION_TYPE, executorType, tx,
              tokenValue, tokenId, blockCap.getInstance(), repository, 0, 0);
      this.vm = new VM();
      this.program = new Program(ops, programInvoke, rootInternalTransaction, vmConfig);
      byte[] txId = TransactionUtil.getTransactionId(tx).getBytes();
      this.program.setRootTransactionId(txId);
      if (enableEventListener && isCheckTransaction()) {
        logInfoTriggerParser = new LogInfoTriggerParser(blockCap.getNum(), blockCap.getTimeStamp(), txId, callerAddress);
      }
    } catch (Exception e) {
      log.info(e.getMessage());
      throw new ContractValidateException(e.getMessage());
    }
    program.getResult().setContractAddress(contractAddress);
    repository.createAccount(contractAddress, newSmartContract.getName(), Protocol.AccountType.Contract);
    repository.createContract(contractAddress, new ContractCapsule(newSmartContract));
    byte[] code = newSmartContract.getBytecode().toByteArray();
    if (!VmConfig.allowTvmConstantinople()) {
      repository.saveCode(contractAddress, ProgramPrecompile.getCode(code));
    }
  }

  private void call() throws ContractValidateException {
    TriggerSmartContract contract = ContractCapsule.getTriggerContractFromTransaction(tx);
    if (contract == null) {
      return;
    }
    if (contract.getContractAddress() == null) {
      throw new ContractValidateException("Cannot get contract address from TriggerContract");
    }
    byte[] contractAddress = contract.getContractAddress().toByteArray();
    ContractCapsule deployedContract = repository.getContract(contractAddress);
    if (null == deployedContract) {
      log.info("No contract or not a smart contract");
      throw new ContractValidateException("No contract or not a smart contract");
    }
    long tokenValue = 0;
    long tokenId = 0;
    byte[] callerAddress = contract.getOwnerAddress().toByteArray();
    byte[] code = repository.getCode(contractAddress);
    if (isNotEmpty(code)) {
      ProgramInvoke programInvoke = programInvokeFactory.createProgramInvoke(InternalTransaction.TxType.TX_CONTRACT_CALL_TYPE,
              executorType, tx, tokenValue, tokenId, blockCap.getInstance(), repository, 0, 0);
      if (isConstantCall) {
        programInvoke.setConstantCall();
      }
      this.vm = new VM();
      rootInternalTransaction = new InternalTransaction(tx, txType);
      this.program = new Program(code, programInvoke, rootInternalTransaction, vmConfig);
      byte[] txId = TransactionUtil.getTransactionId(tx).getBytes();
      this.program.setRootTransactionId(txId);
      if (enableEventListener && isCheckTransaction()) {
        logInfoTriggerParser = new LogInfoTriggerParser(blockCap.getNum(), blockCap.getTimeStamp(), txId, callerAddress);
      }
    }
    program.getResult().setContractAddress(contractAddress);
  }

  private double getCpuLimitInUsRatio() {
    double cpuLimitRatio;
    if (InternalTransaction.ExecutorType.ET_NORMAL_TYPE == executorType) {
      // self witness generates block
      if (this.blockCap != null && blockCap.generatedByMyself &&
          !this.blockCap.hasMasterSignature()) {
        cpuLimitRatio = 1.0;
      } else {
        // self witness or other witness or fullnode verifies block
        if (tx.getRet(0).getContractRet() == Protocol.Transaction.Result.ContractResult.OUT_OF_TIME) {
          cpuLimitRatio = DBConfig.getMinTimeRatio();
        } else {
          cpuLimitRatio = DBConfig.getMaxTimeRatio();
        }
      }
    } else {
      // self witness or other witness or fullnode receives tx
      cpuLimitRatio = 1.0;
    }
    return cpuLimitRatio;
  }

  private boolean isCheckTransaction() {
    return this.blockCap != null && !this.blockCap.getInstance().getBlockHeader().getMasterSignature().isEmpty();
  }
}
