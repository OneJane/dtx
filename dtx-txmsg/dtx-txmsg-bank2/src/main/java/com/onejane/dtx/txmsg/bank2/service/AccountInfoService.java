package com.onejane.dtx.txmsg.bank2.service;


import com.onejane.dtx.txmsg.bank2.model.AccountChangeEvent;

/**
 * Created by Administrator.
 */
public interface AccountInfoService {

    //更新账户，增加金额
    public void addAccountInfoBalance(AccountChangeEvent accountChangeEvent);
}
