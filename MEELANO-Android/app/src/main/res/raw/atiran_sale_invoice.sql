SET NOCOUNT ON;
SET XACT_ABORT ON;
-- Final sales invoice, written the way the Atiran program itself writes one:
--   1. dbo.AddInvoice              header in sailfact, invoice number from InvoiceNumberCounter,
--                                  the customer's debit row in cust_act (act_id 20) and FixManCustomer
--   2. dbo.subsailtemp (one row at a time)  Atiran's InvoiceTrigger copies each line into
--                                  subsailfact and writes the stock movement into ka_act (act_id 20)
--   3. UpdateMojodiInventory / UpdateMojodiInventoryAnbars  recalculate stock of every sold item
--   4. dbo.FactorConfirmation      makes the invoice final (Status=1, TaeedUser, visitor commission)
-- The client's UniqueID makes a repeated send return the first invoice instead of a second one.
DECLARE @shmo int = ?, @vis int = ?, @uid int = ?, @user nvarchar(100) = ?, @date char(10) = ?,
        @desc nvarchar(500) = ?, @uniq nvarchar(50) = ?, @lines nvarchar(max) = ?, @gdisc money = ?, @modpar int = ?;

DECLARE @old bigint = (SELECT TOP (1) shfacfo FROM dbo.sailfact WHERE UniqueID = @uniq AND active = 't' ORDER BY shfacfo DESC);
IF @old IS NOT NULL
BEGIN
    SELECT @old AS shfacfo, 1 AS duplicate,
           ISNULL((SELECT TOP (1) [Status] FROM dbo.sailfact WHERE shfacfo = @old AND active = 't'), 0) AS confirmed,
           (SELECT TOP (1) man FROM dbo.CUSTOMERS WHERE SHMO = @shmo) AS man, N'' AS note,
           (SELECT TOP (1) [all] FROM dbo.sailfact WHERE shfacfo = @old AND active = 't') AS total;
    RETURN;
END

IF NOT EXISTS (SELECT 1 FROM dbo.CUSTOMERS WHERE SHMO = @shmo)
BEGIN
    RAISERROR (N'مشتري در آتيران پيدا نشد', 16, 1);
    RETURN;
END

DECLARE @x xml = CAST(@lines AS xml);
DECLARE @L TABLE (n int IDENTITY(0, 1) PRIMARY KEY, shka bigint, tv decimal(18, 3), tj int, vp money, jp money,
                  pk nvarchar(25), ls money, pt decimal(18, 2), lt money, ptx decimal(18, 2), tx money,
                  nk nvarchar(1000) NULL, mohvah bigint NULL);
INSERT INTO @L (shka, tv, tj, vp, jp, pk, ls, pt, lt, ptx, tx)
SELECT t.c.value('@s', 'bigint'), t.c.value('@tv', 'decimal(18,3)'), ISNULL(t.c.value('@tj', 'int'), 0),
       t.c.value('@vp', 'money'), t.c.value('@jp', 'money'), ISNULL(t.c.value('@pk', 'nvarchar(25)'), N''),
       t.c.value('@ls', 'money'), ISNULL(t.c.value('@pt', 'decimal(18,2)'), 0), ISNULL(t.c.value('@lt', 'money'), 0),
       ISNULL(t.c.value('@ptx', 'decimal(18,2)'), 0), ISNULL(t.c.value('@tx', 'money'), 0)
FROM @x.nodes('/i/l') AS t(c);

UPDATE L SET nk = i.naka, mohvah = ISNULL(NULLIF(i.mohvah, 0), 1)
FROM @L AS L JOIN dbo.inventory AS i ON i.shka = L.shka;

IF NOT EXISTS (SELECT 1 FROM @L)
BEGIN
    RAISERROR (N'فاکتور هيچ کالايي ندارد', 16, 1);
    RETURN;
END
IF EXISTS (SELECT 1 FROM @L WHERE nk IS NULL)
BEGIN
    RAISERROR (N'يکي از کالاها در آتيران پيدا نشد', 16, 1);
    RETURN;
END
IF EXISTS (SELECT 1 FROM @L WHERE tv * mohvah + tj <= 0 OR vp < 0 OR jp < 0 OR ls < 0)
BEGIN
    RAISERROR (N'تعداد يا قيمت يکي از رديف‌ها معتبر نيست', 16, 1);
    RETURN;
END

DECLARE @sum money = (SELECT SUM(ls) FROM @L);
DECLARE @ltaf money = (SELECT SUM(lt) FROM @L);
DECLARE @tax money = (SELECT SUM(tx) FROM @L);
DECLARE @vazn decimal(18, 2) = (SELECT SUM(tv) FROM @L);
DECLARE @tafif money = @ltaf + ISNULL(@gdisc, 0);
DECLARE @all money = @sum - @tafif + @tax;
IF @all < 0
BEGIN
    RAISERROR (N'تخفيف از جمع فاکتور بيشتر است', 16, 1);
    RETURN;
END

DECLARE @moname nvarchar(500) = (SELECT TOP (1) MONAME FROM dbo.CUSTOMERS WHERE SHMO = @shmo);
-- Header texts and print settings are taken from the last invoice Atiran wrote itself.
DECLARE @panevis nvarchar(1000), @tahbarg int, @nahpar int;
SELECT TOP (1) @panevis = panevis, @tahbarg = rdf_tahbarg, @nahpar = nah_par
FROM dbo.sailfact WHERE active = 't' AND ISNULL(DocumentSourceID, 1) = 1 ORDER BY shfacfo DESC;
SET @panevis = ISNULL(@panevis, N'ذکر نشده');
SET @tahbarg = ISNULL(@tahbarg, 2);
SET @nahpar = ISNULL(@nahpar, 2);
DECLARE @anbar int = ISNULL((SELECT TOP (1) rdf_anbar FROM dbo.anbars WHERE ISNULL(Active, 1) = 1
                             ORDER BY CASE WHEN Base = 1 THEN 0 ELSE 1 END, rdf_anbar), 1);
DECLARE @id bigint;

BEGIN TRANSACTION;
EXEC dbo.AddInvoice
     @username = @user, @date = @date, @shmo = @shmo, @barbari = 0, @description = @desc, @vis_rdf = @vis,
     @sumlineall = @sum, @all = @all, @tafif = @tafif, @SumTafifAghlam = @ltaf, @done_date = @date,
     @panevis = @panevis, @modpar = @modpar, @rdf_tahbarg = @tahbarg, @nah_par = @nahpar, @nah_d_text = N'',
     @driver_name = '', @rdf_driver = 0, @mamorp_name = '', @rdf_mamorp = 0, @bamandeh = 1, @shpish = N'',
     @batarikh = 0, @chap_f = 0, @chap_h = 0, @tax = @tax, @moname = @moname, @nahve_namayesh_daryaft = 0,
     @vazn = @vazn, @avarez = 0, @sysid = 1, @userid = @uid, @id_en = @id OUTPUT, @VisitorPoorsant = 0,
     @ShSanadFerestande = N'', @ExternalCosts = 0, @HajmiOverall = 0, @chapWithTasvie = 1, @chapWithMande = 1,
     @MultiPishFactor = 0, @UniqueID = @uniq, @SourceID = 1;
IF @id IS NULL
BEGIN
    ROLLBACK TRANSACTION;
    RAISERROR (N'آتيران شماره فاکتور را برنگرداند', 16, 1);
    RETURN;
END

-- Atiran's InvoiceTrigger reads single values from "inserted", so the lines go in one by one.
DECLARE @n int = 0, @cnt int = (SELECT COUNT(*) FROM @L);
WHILE @n < @cnt
BEGIN
    INSERT INTO dbo.subsailtemp
        (rdf__, shfacfo, shka, rdf_anbar, tedvah, tedjoz, vahprice, jozprice, bastebandi, tedbastebandi, linesum,
         pertafif, RDF, pervis, litakhma, active, naka, ptax, tax, avarez, PAvarez, gift, [date], done_date, UserID,
         vis_rdf, sysid, tafifAghlam, TafifLine, ProductionSeriesID, TEDVAHMain, TEDJOZMain, PerPromotion,
         PromotionValue, MultiPishFactor, TafifPos, TafifNaghd, VarietyID)
    SELECT 1, @id, shka, @anbar, tv, tj, vp, jp, pk, 0, ls,
           pt, n, 0, lt, 't', nk, ptx, tx, 0, 0, 0, @date, @date, @uid,
           @vis, 1, 0, lt, NULL, tv, tj, 0,
           0, 0, 0, 0, shka
    FROM @L WHERE n = @n;
    SET @n = @n + 1;
END

DECLARE @k bigint;
DECLARE meelano_stock CURSOR LOCAL FAST_FORWARD FOR SELECT DISTINCT shka FROM @L;
OPEN meelano_stock;
FETCH NEXT FROM meelano_stock INTO @k;
WHILE @@FETCH_STATUS = 0
BEGIN
    EXEC dbo.UpdateMojodiInventory @k;
    EXEC dbo.UpdateMojodiInventoryAnbars @k;
    FETCH NEXT FROM meelano_stock INTO @k;
END
CLOSE meelano_stock;
DEALLOCATE meelano_stock;
COMMIT TRANSACTION;

-- Final ("قطعي") the same way Atiran's confirm button does it. If confirming fails the invoice
-- is still registered (stock and customer balance are already updated) and the reason is returned.
DECLARE @note nvarchar(400) = N'';
BEGIN TRY
    INSERT INTO dbo.FactorConfirmation ([Date], UserName, Shfacfo, SysID, UserID)
    VALUES (@date, @user, @id, 1, @uid);
END TRY
BEGIN CATCH
    IF XACT_STATE() <> 0 ROLLBACK TRANSACTION;
    SET @note = LEFT(ERROR_MESSAGE(), 400);
END CATCH

SELECT @id AS shfacfo, 0 AS duplicate,
       ISNULL((SELECT TOP (1) [Status] FROM dbo.sailfact WHERE shfacfo = @id AND active = 't'), 0) AS confirmed,
       (SELECT TOP (1) man FROM dbo.CUSTOMERS WHERE SHMO = @shmo) AS man, @note AS note, @all AS total;
