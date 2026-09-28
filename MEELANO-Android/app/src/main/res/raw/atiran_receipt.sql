SET NOCOUNT ON;
SET XACT_ABORT ON;
-- Receipt («قبض دريافت»), written the way the Atiran program itself writes one:
--   1. dbo.Daryaft                  receipt header in dar (number = last receipt + 1), the cash row in
--                                   cust_act (act_id 1) and in the cash box COW, then FixManCustomer
--   2. dbo.PosDetails (one per row)  card reader / card-to-card / bank transfer / havaleh; Atiran's
--                                   PutBan_act trigger writes the bank row (ban_act act 8), the
--                                   customer row (cust_act act 1), FixManBank and FixManCustomer
--   3. dbo.TemplateDaryaftCheque    one per cheque; Atiran's PutGetcheck trigger writes getchk
--                                   (with the Sayad id and «استعلام ثبت در سامانه») and cust_act act 3
--   4. settlement                   dar.shfac for one invoice, DaryaftMultiFactor for several,
--                                   sailfact.MabDaryaftFactor and tasvieh like Atiran's EditInvoice,
--                                   then FixTasvie when setting 95 says «balance settles invoices»
--   5. SabtSanadHesabdariFromAtiran 4  the accounting document, exactly as after a receipt in Atiran
-- The client's UniqueID (dar.UniqueID) makes a repeated send return the first receipt.
DECLARE @shmo int = ?, @uid int = ?, @user nvarchar(100) = ?, @date char(10) = ?, @desc nvarchar(450) = ?,
        @uniq nvarchar(80) = ?, @cash money = ?, @spec nvarchar(max) = ?;

DECLARE @old int = (SELECT TOP (1) ghno FROM dbo.dar WHERE p = 0 AND Active = 1 AND UniqueID = @uniq ORDER BY ghno DESC);
IF @old IS NOT NULL
BEGIN
    SELECT @old AS ghno, 1 AS duplicate, (SELECT TOP (1) man FROM dbo.CUSTOMERS WHERE SHMO = @shmo) AS man,
           ISNULL((SELECT TOP (1) mab FROM dbo.dar WHERE p = 0 AND Active = 1 AND ghno = @old), 0) AS total,
           0 AS settled, N'' AS note;
    RETURN;
END

IF NOT EXISTS (SELECT 1 FROM dbo.CUSTOMERS WHERE SHMO = @shmo)
BEGIN
    RAISERROR (N'مشتري در آتيران پيدا نشد', 16, 1);
    RETURN;
END

DECLARE @x xml = CAST(@spec AS xml);
DECLARE @P TABLE (n int IDENTITY(0, 1) PRIMARY KEY, kind nvarchar(10), m money, b int, t nvarchar(100), d nvarchar(300));
DECLARE @C TABLE (n int IDENTITY(0, 1) PRIMARY KEY, m money, s nvarchar(50), sd nvarchar(10), bn nvarchar(100),
                  br nvarchar(200), hs nvarchar(50), sy nvarchar(50), ri bit, ct int, d nvarchar(300));
DECLARE @F TABLE (n int IDENTITY(0, 1) PRIMARY KEY, f bigint, m money);
INSERT INTO @P (kind, m, b, t, d)
SELECT t.c.value('@k', 'nvarchar(10)'), t.c.value('@m', 'money'), ISNULL(t.c.value('@b', 'int'), 0),
       ISNULL(t.c.value('@t', 'nvarchar(100)'), N''), ISNULL(t.c.value('@d', 'nvarchar(300)'), N'')
FROM @x.nodes('/r/p') AS t(c);
INSERT INTO @C (m, s, sd, bn, br, hs, sy, ri, ct, d)
SELECT t.c.value('@m', 'money'), ISNULL(t.c.value('@s', 'nvarchar(50)'), N''), ISNULL(t.c.value('@sd', 'nvarchar(10)'), N''),
       ISNULL(t.c.value('@bn', 'nvarchar(100)'), N''), ISNULL(t.c.value('@br', 'nvarchar(200)'), N''),
       ISNULL(t.c.value('@hs', 'nvarchar(50)'), N''), ISNULL(t.c.value('@sy', 'nvarchar(50)'), N''),
       ISNULL(t.c.value('@ri', 'bit'), 0), ISNULL(NULLIF(t.c.value('@ct', 'int'), 0), 1), ISNULL(t.c.value('@d', 'nvarchar(300)'), N'')
FROM @x.nodes('/r/c') AS t(c);
INSERT INTO @F (f, m)
SELECT t.c.value('@n', 'bigint'), t.c.value('@m', 'money') FROM @x.nodes('/r/f') AS t(c);
DELETE FROM @P WHERE ISNULL(m, 0) <= 0;
DELETE FROM @C WHERE ISNULL(m, 0) <= 0;
DELETE FROM @F WHERE ISNULL(m, 0) <= 0 OR f NOT IN (SELECT shfacfo FROM dbo.sailfact WHERE active = 't' AND shmo = @shmo);

SET @cash = ISNULL(@cash, 0);
IF @cash < 0 SET @cash = 0;
DECLARE @pos money = ISNULL((SELECT SUM(m) FROM @P WHERE kind = N'pos'), 0);
DECLARE @hav money = ISNULL((SELECT SUM(m) FROM @P WHERE kind <> N'pos'), 0);
DECLARE @chk money = ISNULL((SELECT SUM(m) FROM @C), 0);
DECLARE @ted int = (SELECT COUNT(*) FROM @C);
IF @cash + @pos + @hav + @chk <= 0
BEGIN
    RAISERROR (N'مبلغ دريافت صفر است', 16, 1);
    RETURN;
END
IF EXISTS (SELECT 1 FROM @P WHERE b NOT IN (SELECT RDF FROM dbo.BANK WHERE ISNULL(Active, 1) = 1))
BEGIN
    RAISERROR (N'حساب بانکي انتخاب شده در آتيران فعال نيست', 16, 1);
    RETURN;
END
IF EXISTS (SELECT 1 FROM @C WHERE s = N'' OR LEN(sd) <> 10 OR bn = N'')
BEGIN
    RAISERROR (N'براي هر چک، شماره، تاريخ سررسيد و بانک لازم است', 16, 1);
    RETURN;
END

DECLARE @moname nvarchar(500) = (SELECT TOP (1) MONAME FROM dbo.CUSTOMERS WHERE SHMO = @shmo);
-- Receipt kind («شرح دريافت»): the one Atiran used for its last receipt.
DECLARE @dtype int = (SELECT TOP (1) darDescriptionTypeID FROM dbo.dar WHERE p = 0 AND Active = 1 AND darDescriptionTypeID IS NOT NULL ORDER BY ghno DESC);
IF @dtype IS NULL SET @dtype = (SELECT TOP (1) rowId FROM dbo.darDescriptionType WHERE p = 0 ORDER BY rowId);
DECLARE @one bigint = CASE WHEN (SELECT COUNT(*) FROM @F) = 1 THEN (SELECT TOP (1) f FROM @F) ELSE 0 END;
DECLARE @gh int;

BEGIN TRANSACTION;
EXEC dbo.Daryaft
     @shmo = @shmo, @mabNaghdi = @cash, @mabPos = @pos, @mabHavaleh = @hav, @tafif = 0, @SumMabcheck = @chk,
     @TedadChk = @ted, @DarDesc = @desc, @date = @date, @donedate = @date, @user = @user,
     @rdfAvarndeVajhVisitor = 0, @rdfAvarndeVajhMP = 0, @rdfAvarndeVajhMM = 0, @rdfAvarndeVajhDriver = 0,
     @shfacfo = @one, @sysID = 1, @takhfifHazineh = 0, @takhfifDaramad = 0, @rdf_daryaft = @gh OUTPUT,
     @FakeUser = @user, @SubmitUserID = @uid, @OtherPrice = 0, @PeygiriMotalebatID = NULL,
     @DescriptionReceiveId = @dtype, @UniqueID = @uniq;
IF @gh IS NULL
BEGIN
    ROLLBACK TRANSACTION;
    RAISERROR (N'آتيران شماره قبض را برنگرداند', 16, 1);
    RETURN;
END

-- Atiran's triggers read single values from "inserted", so every row goes in on its own.
DECLARE @n int = 0, @cnt int = (SELECT COUNT(*) FROM @P);
WHILE @n < @cnt
BEGIN
    INSERT INTO dbo.PosDetails (ghno, p, MabPos, PosBankRdf, PosDesc, ShPeigiri, IsHavaleh, Rdf_, Karmozd, ZirSanad,
                                RdfZirSarFasl, UserID, TerminalID, isEdited)
    SELECT @gh, 0, m, b, d, t, CASE WHEN kind = N'pos' THEN 0 ELSE 1 END, 1, NULL, NULL, NULL, @uid, NULL, 0
    FROM @P WHERE n = @n;
    SET @n = @n + 1;
END

SET @n = 0; SET @cnt = (SELECT COUNT(*) FROM @C);
WHILE @n < @cnt
BEGIN
    INSERT INTO dbo.TemplateDaryaftCheque (BankName, Shmo, Mab, Shhesab, Shobe, ShSerial, SarDate, DarDate, [Desc], Ghno,
                                           Shahrestan, SysID, Shfacfo, RdfVisitor, RdfMP, RdfMM, RdfDriver, Moname, ID,
                                           DoneDate, Rdf_, UserID, ShenaseSayad, RegistrationInquiry, MoeinId, CheckTypeID)
    SELECT bn, @shmo, m, hs, br, s, sd, @date, d, @gh,
           0, 1, @one, 0, 0, 0, 0, @moname, n + 1,
           @date, 1, @uid, sy, ri, NULL, ct
    FROM @C WHERE n = @n;
    SET @n = @n + 1;
END

-- Which invoices this receipt pays.
IF (SELECT COUNT(*) FROM @F) > 1
    INSERT INTO dbo.DaryaftMultiFactor (GhnoDar, Rdf_, Shfacfo, Price, Tafif, IsTasvieh)
    SELECT @gh, 1, F.f, F.m, 0,
           CASE WHEN ISNULL(s.[all], 0) <= ISNULL(s.MabDaryaftFactor, 0) + ISNULL(s.tdf, 0) + F.m THEN 1 ELSE 0 END
    FROM @F AS F JOIN dbo.sailfact AS s ON s.shfacfo = F.f AND s.active = 't';
UPDATE s SET MabDaryaftFactor = ISNULL(s.MabDaryaftFactor, 0) + F.m
FROM dbo.sailfact AS s JOIN @F AS F ON F.f = s.shfacfo WHERE s.active = 't';
UPDATE s SET tasvieh = 't'
FROM dbo.sailfact AS s JOIN @F AS F ON F.f = s.shfacfo
WHERE s.active = 't' AND ISNULL(s.[all], 0) <= ISNULL(s.MabDaryaftFactor, 0) + ISNULL(s.tdf, 0);
IF (SELECT TOP (1) value FROM dbo.overal_setting WHERE id = 95) = 1
    EXEC dbo.FixTasvie @shmo;
COMMIT TRANSACTION;

-- Accounting document the way Atiran does it after a receipt (it does nothing unless accounting is on).
-- A failure here keeps the receipt and returns the reason.
DECLARE @note nvarchar(400) = N'';
IF ISNULL((SELECT TOP (1) IsFinal FROM dbo.dar WHERE p = 0 AND Active = 1 AND ghno = @gh), 1) = 1
BEGIN
    BEGIN TRY
        EXEC dbo.SabtSanadHesabdariFromAtiran @sanadType = 4, @atiranSanadNumber = @gh, @userID = @uid, @sysID = 1;
    END TRY
    BEGIN CATCH
        IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
        SET @note = LEFT(ERROR_MESSAGE(), 400);
    END CATCH
END

SELECT @gh AS ghno, 0 AS duplicate, (SELECT TOP (1) man FROM dbo.CUSTOMERS WHERE SHMO = @shmo) AS man,
       @cash + @pos + @hav + @chk AS total,
       (SELECT COUNT(*) FROM dbo.sailfact WHERE active = 't' AND tasvieh = 't' AND shfacfo IN (SELECT f FROM @F)) AS settled,
       @note AS note;
