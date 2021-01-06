package cn.ledgeryi.framework.common.storage;

import cn.ledgeryi.chainbase.core.capsule.*;
import cn.ledgeryi.common.runtime.vm.DataWord;
import cn.ledgeryi.contract.vm.program.Storage;
import cn.ledgeryi.contract.vm.repository.Key;
import cn.ledgeryi.contract.vm.repository.Value;
import cn.ledgeryi.framework.core.db.Manager;
import cn.ledgeryi.protos.Protocol;

public interface Deposit {

  Manager getDbManager();

  AccountCapsule createAccount(byte[] address, Protocol.AccountType type);

  AccountCapsule createAccount(byte[] address, String accountName, Protocol.AccountType type);

  AccountCapsule getAccount(byte[] address);

  MasterCapsule getWitness(byte[] address);

  void deleteContract(byte[] address);

  void createContract(byte[] address, ContractCapsule contractCapsule);

  ContractCapsule getContract(byte[] address);

  void updateContract(byte[] address, ContractCapsule contractCapsule);

  void updateAccount(byte[] address, AccountCapsule accountCapsule);

  void saveCode(byte[] address, byte[] code);

  byte[] getCode(byte[] address);

  void putStorageValue(byte[] address, DataWord key, DataWord value);

  DataWord getStorageValue(byte[] address, DataWord key);

  Storage getStorage(byte[] address);

  long getBalance(byte[] address);

  long addBalance(byte[] address, long value);

  Deposit newDepositChild();

  void setParent(Deposit deposit);

  void commit();

  void putAccount(Key key, Value value);

  void putTransaction(Key key, Value value);

  void putBlock(Key key, Value value);

  void putWitness(Key key, Value value);

  void putCode(Key key, Value value);

  void putContract(Key key, Value value);

  void putStorage(Key key, Storage cache);

  TransactionCapsule getTransaction(byte[] trxHash);

  BlockCapsule getBlock(byte[] blockHash);

}

